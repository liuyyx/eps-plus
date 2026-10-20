package com.github.epsilon.ysm;

import com.github.epsilon.Constants;
import com.github.epsilon.managers.ConfigManager;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * YSM 模型资源定位/解包。
 *
 * <p>内置模型随 jar 打包在 {@code assets/epsilon/ysm/builtin/<name>/}。
 * 开发环境（Loom）中该路径是目录，可直接读取；打包后是 jar 内条目，
 * 必须先解包到缓存目录再交给基于 {@link Path} 的反序列化器。</p>
 */
public final class YsmResources {

    /** 内置模型在资源包中的根路径。 */
    public static final String BUILTIN_ROOT = "/assets/epsilon/ysm/builtin/";
    /** 内置模型的资源包内前缀（无前导斜杠）。 */
    private static final String BUILTIN_PREFIX = "assets/epsilon/ysm/builtin/";
    /** 完整性标记：4 个内置模型都带 ysm.json。 */
    private static final String MARKER = "ysm.json";

    private YsmResources() {
    }

    /** 外部模型目录：{@code ~/.epsilon/ysm}。 */
    public static Path root() {
        Path dir = ConfigManager.INSTANCE.getConfigDir().resolve("ysm");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            Constants.LOGGER.warn("YsmModel: cannot create model directory {}", dir, e);
        }
        return dir;
    }

    /**
     * 返回内置模型 {@code name} 的可读目录，必要时先解包到
     * {@code ~/.epsilon/ysm/builtin/<name>}。资源缺失或解包失败返回 {@code null}。
     */
    public static Path builtin(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Path cache = root().resolve("builtin").resolve(name);
        if (isComplete(cache)) {
            return cache;
        }
        URL url = YsmResources.class.getResource(BUILTIN_ROOT + name);
        if (url == null) {
            return null;
        }
        try {
            if ("file".equals(url.getProtocol())) {
                // 开发环境：资源目录直接在磁盘上。
                return Paths.get(url.toURI());
            }
            copyFromArchive(url, name, cache);
        } catch (Exception e) {
            Constants.LOGGER.error("YsmModel: failed to extract builtin model {}", name, e);
        }
        return isComplete(cache) ? cache : null;
    }

    /** 只有解出完整内容才算可用，避免把中断的半成品缓存当成模型目录。 */
    private static boolean isComplete(Path cache) {
        return Files.isRegularFile(cache.resolve(MARKER));
    }

    private static void copyFromArchive(URL url, String name, Path cache) throws Exception {
        String prefix = BUILTIN_PREFIX + name + "/";
        Path archive = archivePath(url);
        if (archive != null && Files.isRegularFile(archive)) {
            // 直接用 ZipFile 读，绕开 zipfs 的进程级注册表：mod jar 往往已被
            // Fabric Loader 或其它 mod 以 zipfs 形式打开，再 newFileSystem 会抛
            // FileSystemAlreadyExistsException。
            int copied = 0;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entry.isDirectory() || !entryName.startsWith(prefix)) {
                        continue;
                    }
                    Path target = cache.resolve(entryName.substring(prefix.length()));
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    copied++;
                }
            }
            if (copied > 0) {
                return;
            }
        }
        copyViaZipFileSystem(url, prefix, cache);
    }

    /** 兜底：nested/自定义协议的存档。已存在的 zipfs 借用而不关闭。 */
    private static void copyViaZipFileSystem(URL url, String prefix, Path cache) throws Exception {
        URI uri = url.toURI();
        FileSystem fs;
        boolean owned = false;
        try {
            fs = FileSystems.getFileSystem(uri);
        } catch (FileSystemNotFoundException e) {
            fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
            owned = true;
        }
        try {
            Path source = fs.getPath("/" + prefix);
            if (!Files.isDirectory(source)) {
                return;
            }
            Files.createDirectories(cache);
            try (Stream<Path> stream = Files.walk(source)) {
                for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    Path target = cache.resolve(source.relativize(path).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            if (owned) {
                fs.close();
            }
        }
    }

    /** 由 {@code jar:file:/…/mod.jar!/assets/…} 解析出存档路径。 */
    private static Path archivePath(URL url) {
        String value = url.toString();
        if (!value.startsWith("jar:")) {
            return null;
        }
        int separator = value.indexOf("!/");
        if (separator < 0) {
            return null;
        }
        try {
            return Paths.get(URI.create(value.substring(4, separator)));
        } catch (Exception ignored) {
            return null;
        }
    }
}
