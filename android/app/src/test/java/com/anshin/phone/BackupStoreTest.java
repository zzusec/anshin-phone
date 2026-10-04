package com.anshin.phone;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 备份原子提交回归：失败保留旧恢复点，中断可恢复，损坏备份拒绝恢复。 */
public class BackupStoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File validApk(File dir, String name, String content) throws Exception {
        File apk = new File(dir, name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return apk;
    }

    @Test
    public void backupWritesManifestAndFilesAtomically() throws Exception {
        tmp.create();
        File root = tmp.newFolder("data");
        File apk = validApk(tmp.newFolder("src"), "base.apk", "payload");
        BackupStore.backupFiles(root, "com.example.a", Arrays.asList(apk.getAbsolutePath()), 5L, false);
        BackupStore.readManifest(root, "com.example.a"); // 完整且校验通过
        assertTrue(new File(root, "backups/com.example.a/manifest.json").isFile());
        // 没有遗留 staging/old 目录
        assertEquals(1, new File(root, "backups").listFiles().length);
        tmp.delete();
    }

    @Test
    public void failedSecondBackupKeepsPreviousRestorePoint() throws Exception {
        tmp.create();
        File root = tmp.newFolder("data");
        File apk1 = validApk(tmp.newFolder("src1"), "base.apk", "first");
        BackupStore.backupFiles(root, "com.example.a", Arrays.asList(apk1.getAbsolutePath()), 1L, false);
        // 第二次备份源文件缺失 → 失败，旧备份必须原样可用
        assertThrows(Exception.class, () ->
                BackupStore.backupFiles(root, "com.example.a", Arrays.asList("/nonexistent/base.apk"), 2L, false));
        BackupStore.readManifest(root, "com.example.a");
        assertEquals(1, new File(root, "backups").listFiles().length);
        tmp.delete();
    }

    @Test
    public void invalidApkContentIsRejected() throws Exception {
        tmp.create();
        File root = tmp.newFolder("data");
        File notApk = tmp.newFile("base.apk");
        Files.write(notApk.toPath(), "definitely not a zip".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () ->
                BackupStore.backupFiles(root, "com.example.a", Arrays.asList(notApk.getAbsolutePath()), 1L, false));
        tmp.delete();
    }

    @Test
    public void manifestDetectsCorruptedBackupFiles() throws Exception {
        tmp.create();
        File root = tmp.newFolder("data");
        File apk = validApk(tmp.newFolder("src"), "base.apk", "payload");
        BackupStore.backupFiles(root, "com.example.a", Arrays.asList(apk.getAbsolutePath()), 1L, false);
        // 备份内容被篡改 → 恢复前校验失败
        File stored = new File(root, "backups/com.example.a/base.apk");
        Files.write(stored.toPath(), "tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> BackupStore.readManifest(root, "com.example.a"));
        tmp.delete();
    }

    @Test
    public void interruptedCommitRecoversPreviousBackup() throws Exception {
        tmp.create();
        File root = tmp.newFolder("data");
        File apk = validApk(tmp.newFolder("src"), "base.apk", "old-copy");
        BackupStore.backupFiles(root, "com.example.a", Arrays.asList(apk.getAbsolutePath()), 1L, false);
        // 模拟提交中断：新目录未就位，旧目录滞留在 .old-
        File dir = new File(root, "backups/com.example.a");
        File old = new File(root, "backups/com.example.a.old-123");
        assertTrue(dir.renameTo(old));
        BackupStore.readManifest(root, "com.example.a"); // 应触发恢复并校验通过
        assertTrue(new File(dir, "manifest.json").isFile());
        assertEquals(1, new File(root, "backups").listFiles().length);
        tmp.delete();
    }

    @Test
    public void rejectsInvalidPackageNames() throws Exception {
        tmp.create();
        File root = tmp.newFolder("data");
        File apk = validApk(tmp.newFolder("src"), "base.apk", "payload");
        assertThrows(SecurityException.class, () ->
                BackupStore.backupFiles(root, "../escape", Arrays.asList(apk.getAbsolutePath()), 1L, false));
        assertThrows(SecurityException.class, () -> BackupStore.readManifest(root, "com.example.a/../../x"));
        tmp.delete();
    }

    @Test
    public void splitApksAllRecorded() throws Exception {
        tmp.create();
        File root = tmp.newFolder("data");
        File base = validApk(tmp.newFolder("src"), "base.apk", "base");
        File split = validApk(tmp.newFolder("src2"), "split.apk", "split");
        List<String> sources = Arrays.asList(base.getAbsolutePath(), split.getAbsolutePath());
        Object manifest = BackupStore.backupFiles(root, "com.example.a", sources, 2L, true);
        assertNotNull(manifest);
        BackupStore.readManifest(root, "com.example.a");
        assertTrue(new File(root, "backups/com.example.a/split-1.apk").isFile());
        tmp.delete();
    }
}
