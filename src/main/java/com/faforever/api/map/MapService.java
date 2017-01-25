package com.faforever.api.map;

import com.faforever.api.config.FafApiProperties;
import com.faforever.api.content.ContentService;
import com.faforever.api.data.domain.Map;
import com.faforever.api.data.domain.MapVersion;
import com.faforever.api.data.domain.Player;
import com.faforever.api.error.ApiException;
import com.faforever.api.error.Error;
import com.faforever.api.error.ErrorCode;
import com.faforever.api.utils.JavaFxUtil;
import com.faforever.api.utils.PreviewGenerator;
import com.faforever.api.utils.Unzipper;
import com.faforever.api.utils.Zipper;
import javafx.scene.image.Image;
import lombok.Data;
import lombok.SneakyThrows;
import org.luaj.vm2.LuaValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.faforever.api.utils.LuaUtil.loadFile;
import static com.github.nocatch.NoCatch.noCatch;

@Service
public class MapService {
  private static final float MAP_SIZE_FACTOR = 51.2f;
  private static final String[] REQUIRED_FILES = new String[]{
      ".scmap",
      "_save.lua",
      "_scenario.lua",
      "_script.lua"};
  private static final String[] INVALID_MAP_NAME = new String[]{
      "save",
      "script",
      "map",
      "tables"
  };
  public static final Charset MAP_CHARSET = StandardCharsets.ISO_8859_1;
  private final FafApiProperties fafApiProperties;
  private final MapRepository mapRepository;
  private final ContentService contentService;
  private final Pattern luaMapPattern;

  @Inject
  public MapService(FafApiProperties fafApiProperties, MapRepository mapRepository, ContentService contentService) {
    this.fafApiProperties = fafApiProperties;
    this.mapRepository = mapRepository;
    this.contentService = contentService;
    this.luaMapPattern = Pattern.compile("([^/]+)\\.scmap");
  }

  @Transactional
  public void uploadMap(byte[] mapData, String mapFilename, Player author) throws IOException {
    Path finalPath = Paths.get(fafApiProperties.getMap().getFinalDirectory(), mapFilename);

    Path baseDir = contentService.createTempDir();
    Path tmpFile = Paths.get(baseDir.toString(), mapFilename);
    Files.write(tmpFile, mapData);

    try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(Files.newInputStream(tmpFile)))) {
      Unzipper.from(zipInputStream).to(baseDir).unzip();
    }
    Optional<Path> mapFolder;
    try (Stream<Path> mapFolderStream = Files.list(baseDir)) {
      mapFolder = mapFolderStream
          .filter(path -> Files.isDirectory(path))
          .findFirst();
    }

    if (!mapFolder.isPresent()) {
      throw new ApiException(new Error(ErrorCode.MAP_MISSING_MAP_FOLDER_INSIDE_ZIP));
    }
    validateZipFile(mapFolder.get());

    Path scenarioLuaPath;
    // read from Lua File
    try (Stream<Path> mapFilesStream = Files.list(mapFolder.get())) {
      scenarioLuaPath = noCatch(() -> mapFilesStream)
          .filter(myFile -> myFile.toString().endsWith("_scenario.lua"))
          .findFirst()
          .orElseThrow(() -> new ApiException(new Error(ErrorCode.MAP_SCENARIO_LUA_MISSING)));
    }

    LuaValue luaRoot = noCatch(() -> loadFile(scenarioLuaPath), IllegalStateException.class);
    LuaValue scenarioInfo = luaRoot.get("ScenarioInfo");

    String oldMapName = validateName(scenarioInfo);

    Optional<Map> mapEntity = mapRepository.findOneByDisplayName(scenarioInfo.get("name").toString());
    if (mapEntity.isPresent() && mapEntity.get().getAuthor().getId() != author.getId()) {
      throw new ApiException(new Error(ErrorCode.MAP_NOT_ORIGINAL_AUTHOR, mapEntity.get().getDisplayName()));
    }
    int newVersion = scenarioInfo.get("map_version").toint();
    if (mapEntity.isPresent() && mapEntity.get().getVersions().stream()
        .anyMatch(mapVersion -> mapVersion.getVersion() == newVersion)) {
      throw new ApiException(new Error(ErrorCode.MAP_VERSION_EXISTS, mapEntity.get().getDisplayName(), newVersion));
    }

    Map map = mapEntity.orElseGet(Map::new)
        .setDisplayName(scenarioInfo.get("name").toString())
        .setMapType(scenarioInfo.get("type").tojstring())
        .setBattleType(scenarioInfo.get("Configurations").get("standard").get("teams").get(1).get("name").tojstring())
        .setAuthor(author);

    // try to save entity to db to trigger validation
    LuaValue size = scenarioInfo.get("size");
    MapVersion version = new MapVersion()
        .setDescription(scenarioInfo.get("description").tojstring().replaceAll("<LOC .*?>", ""))
        .setWidth((int) (size.get(1).toint() / MAP_SIZE_FACTOR))
        .setHeight((int) (size.get(2).toint() / MAP_SIZE_FACTOR))
        .setHidden(false)
        .setRanked(false) // TODO: read from json data
        .setMaxPlayers(scenarioInfo.get("Configurations").get("standard").get("teams").get(1).get("armies").length())
        .setVersion(scenarioInfo.get("map_version").toint());
    version.setFilename(generateMapName(map, version, "zip"));

    map.getVersions().add(version);
    version.setMap(map);
    mapRepository.save(map);


    // generate preview
    String previewFilename = com.google.common.io.Files.getNameWithoutExtension(mapFilename) + ".png";
    generateImage(Paths.get(
        fafApiProperties.getMap().getMapPreviewPathSmall(), previewFilename),
        mapFolder.get(),
        fafApiProperties.getMap().getPreviewSizeSmall());

    generateImage(Paths.get(
        fafApiProperties.getMap().getMapPreviewPathLarge(), previewFilename),
        mapFolder.get(),
        fafApiProperties.getMap().getPreviewSizeLarge());

    // move to final path
    // TODO: normalize zip file and repack it https://github.com/FAForever/faftools/blob/87f0275b889e5dd1b1545252a220186732e77403/faf/tools/fa/maps.py#L222
    Files.createDirectories(finalPath.getParent());
    enrichMapDataAndZip(mapFolder.get(), oldMapName, version);

    // delete temporary folder
    FileSystemUtils.deleteRecursively(baseDir.toFile());
  }

  // Safety net for the risky replacement below
  private String validateName(LuaValue scenarioInfo) {
    String mapString = scenarioInfo.get("map").toString();
    Matcher matcher = luaMapPattern.matcher(mapString);
    if (!matcher.find()) {
      throw new ApiException(new Error(ErrorCode.MAP_NO_VALID_MAP_NAME, mapString));
    }
    String result = com.google.common.io.Files.getNameWithoutExtension(matcher.group(0));
    Arrays.stream(INVALID_MAP_NAME).forEach(name -> {
      if (name.equalsIgnoreCase(result)) {
        throw new ApiException(new Error(ErrorCode.MAP_NO_VALID_MAP_NAME, result));
      }
    });
    return result;
  }

  private String generateMapName(Map map, MapVersion version, String extension) {
    return Paths.get(String.format("%s.v%04d.%s",
        normalizeMapName(map.getDisplayName()),
        version.getVersion(),
        extension))
        .normalize().toString();
  }

  private String normalizeMapName(String mapName) {
    return Paths.get(mapName.toLowerCase().replaceAll(" ", "_")).normalize().toString();
  }

  @SneakyThrows
  private void enrichMapDataAndZip(Path mapFolder, String oldMapName, MapVersion mapVersion) {
    Path finalPath = Paths.get(fafApiProperties.getMap().getFinalDirectory(), mapVersion.getFilename());
    if (Files.exists(finalPath)) {
      throw new ApiException(new Error(ErrorCode.MAP_NAME_CONFLICT, mapVersion.getFilename()));
    }
    String newMapName = com.google.common.io.Files.getNameWithoutExtension(mapVersion.getFilename());
    Path newMapFolder = Paths.get(mapFolder.getParent().toString(), newMapName);
    if (!newMapName.equals(oldMapName)) {
      renameFiles(mapFolder, oldMapName, newMapName);
      updateLua(mapFolder, oldMapName, mapVersion.getMap());
      Files.move(mapFolder, newMapFolder);
    }

//    try (ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(finalPath)))) {
//      Zipper.contentOf(mapFolder).to(zipOutputStream).zip();
//    }
  }

  @SneakyThrows
  private void updateLua(Path mapFolder, String oldMapName, Map map) {
    String oldNameFolder = "/maps/" + oldMapName;
    String newNameFolder = "/maps/" + normalizeMapName(map.getDisplayName());
    String oldName = "/" + oldMapName;
    String newName = "/" + normalizeMapName(map.getDisplayName());
    try (Stream<Path> mapFileStream = Files.list(mapFolder)) {
      mapFileStream.forEach(path -> noCatch(() -> {
        List<String> collect = Files.readAllLines(path, MAP_CHARSET).stream()
            .map(line -> line.replaceAll(oldNameFolder, newNameFolder)
                .replaceAll(oldName, newName))
            .collect(Collectors.toList());
        Files.write(path, collect, MAP_CHARSET);
      }));
    }
  }

  @SneakyThrows
  private void renameFiles(Path mapFolder, String oldMapName, String newMapName) {
    try (Stream<Path> mapFileStream = Files.list(mapFolder)) {
      mapFileStream.forEach(path -> {
        String filename = com.google.common.io.Files.getNameWithoutExtension(path.toString());
        if (filename.equalsIgnoreCase(oldMapName)) {
          try {
            Files.move(path, Paths.get(path.getParent().toString(), newMapName));
          } catch (IOException e) {
            throw new ApiException(new Error(ErrorCode.MAP_RENAME_FAILED));
          }
        }
      });
    }
  }

  private void validateZipFile(Path path) throws IOException {
    List<Path> filePaths = new ArrayList<>();
    try (Stream<Path> mapFileStream = Files.list(path)) {
      mapFileStream.forEach(myPath -> filePaths.add(myPath));
      Arrays.stream(REQUIRED_FILES)
          .forEach(filePattern -> {
            if (filePaths.stream()
                .noneMatch(filePath -> filePath.toString().endsWith(filePattern))) {
              throw new ApiException(new Error(ErrorCode.MAP_FILE_INSIDE_ZIP_MISSING, filePattern));
            }
          });
    }
  }

  private void generateImage(Path target, Path baseDir, int size) throws IOException {
    Image image = PreviewGenerator.generatePreview(baseDir, size, size);
    JavaFxUtil.writeImage(image, target, "png");
  }

  // TODO: use this
  @Data
  private class MapUploadData {
    private String uploadFileName;
    private String originalFolderName;
    private String originalMapName;
    private String newFolderName;
    private String newMapName;
    private Path uploadedFile;
    private Path finalFile;
  }
}
