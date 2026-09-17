// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/** App-context-only storage. Failed attempts remain pending and are removed, never published. */
final class DownloadStorage implements AutoCloseable {
    private static final String DOWNLOAD_FOLDER = "DescargaSocial";
    private static final Set<String> ACTIVE = new HashSet<>();
    private static final Object FILE_NAMES = new Object();
    private final Context context;
    private final DownloadControl control;
    private final SharedPreferences preferences;
    private final String taskId;
    private final String uriKey;
    private final String pathKey;

    DownloadStorage(Context context, DownloadControl control, String taskId) throws IOException {
        this.context = context.getApplicationContext();
        this.control = control;
        this.preferences = this.context.getSharedPreferences("download_storage", Context.MODE_PRIVATE);
        if (!taskId.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Identificador de petición inválido.");
        }
        this.taskId = taskId;
        this.uriKey = "pending_uri:" + taskId;
        this.pathKey = "pending_path:" + taskId;
        synchronized (ACTIVE) {
            if (ACTIVE.contains(taskId)) { throw new IOException("Esta petición ya está guardando un archivo."); }
            // Include the pre-1.3 singleton marker for safe upgrade recovery.
            recover("pending_uri", "pending_path");
            Set<String> orphanIds = new HashSet<>();
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith("pending_uri:") || key.startsWith("pending_path:") || key.startsWith("pending_cache:")) {
                    orphanIds.add(key.substring(key.indexOf(':') + 1));
                }
            }
            for (String id : orphanIds) {
                if (!ACTIVE.contains(id)) {
                    recover("pending_uri:" + id, "pending_path:" + id);
                    recoverCache("pending_cache:" + id);
                }
            }
            ACTIVE.add(taskId);
        }
    }

    @Override public void close() { synchronized (ACTIVE) { ACTIVE.remove(taskId); } }

    /** Recover only a precise pending target previously created by this app, after process death. */
    void recoverPending() throws IOException {
        recover(uriKey, pathKey);
        recoverCache("pending_cache:" + taskId);
    }

    private void recoverCache(String key) throws IOException {
        String path=preferences.getString(key,null);
        if(path==null) { return; }
        String id=key.substring("pending_cache:".length());
        if(!id.matches("[A-Za-z0-9-]{1,64}") || !new File(path).getName().equals("youtube-"+id)) {
            throw new StorageException("TEMP_OWNER",Messages.ref("error_141"));
        }
        try { YoutubeWorkFiles.clean(context.getCacheDir(),path); }
        catch(StorageException error) { throw error; }
        catch(IOException error) { throw new StorageException("TEMP_RECOVER",Messages.ref("error_142"),error); }
        if(!preferences.edit().remove(key).commit()) { throw new StorageException("TEMP_REGISTER",Messages.ref("error_143")); }
    }

    private void transfer(MediaItem item, OutputStream output) throws Exception {
        if(item.getPlatform()!=SocialPlatform.YOUTUBE) {
            control.startPhase(Messages.ref("phase_file"),-1);
            SocialExtractor.downloadToStream(item,output); return;
        }
        File directory=YoutubeWorkFiles.folder(context.getCacheDir(),taskId);
        String key="pending_cache:"+taskId;
        if(!preferences.edit().putString(key,directory.getAbsolutePath()).commit()) {
            throw new StorageException("TEMP_REGISTER",Messages.ref("error_144"));
        }
        Exception original=null;
        try {
            if(!directory.mkdir()) { throw new StorageException("TEMP_CREATE",Messages.ref("error_145")); }
            YoutubeDownload.downloadToStream(item,output,directory);
        } catch(Exception error) { original=error; throw error; }
        finally {
            try { recoverCache(key); }
            catch(IOException cleanup) {
                if(original!=null) { original.addSuppressed(cleanup); }
                else { throw cleanup; }
            }
        }
    }

    private void recover(String uriKey, String pathKey) throws IOException {
        String storedUri = preferences.getString(uriKey, null);
        if (storedUri != null && Build.VERSION.SDK_INT >= 29) {
            Uri uri = Uri.parse(storedUri);
            if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())
                    || !uri.getPath().matches("/external_primary/downloads/[0-9]+")) {
                throw new IOException("El destino pendiente no es válido.");
            }
            ContentResolver resolver = context.getContentResolver();
            try (Cursor row = resolver.query(uri, new String[]{MediaStore.MediaColumns.IS_PENDING},
                    null, null, null)) {
                if (row == null) { throw new IOException("Android no pudo consultar el archivo parcial."); }
                if (row.moveToFirst() && row.getInt(0) == 1) {
                    if (resolver.delete(uri, null, null) <= 0) {
                        throw new IOException("Android no pudo retirar el archivo parcial.");
                    }
                }
            }
            preferences.edit().remove(uriKey).commit();
        }
        String storedPath = preferences.getString(pathKey, null);
        if (storedPath != null) {
            File file = new File(storedPath);
            File folder = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FOLDER);
            if (file.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())
                    && file.getName().startsWith(".descargasocial-") && file.getName().endsWith(".part")) {
                if (file.exists() && !file.delete()) {
                    throw new IOException("No se pudo limpiar el archivo parcial anterior.");
                }
            } else { throw new IOException("El destino pendiente no es válido."); }
            preferences.edit().remove(pathKey).commit();
        }
    }

    String saveMedia(MediaItem item, String contentId, int originalIndex) throws Exception {
        String extension = "." + item.getSuggestedExtension();
        String baseName = String.format(
                Locale.US,
                "%s_%s_%02d",
                item.getPlatform().getFilePrefix(),
                contentId,
                originalIndex);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveWithMediaStore(item, baseName, extension);
        }
        return saveLegacy(item, baseName, extension);
    }

    private String saveWithMediaStore(MediaItem item, String baseName, String extension)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER + "/";
        String displayName;
        String mime = item.getMimeType();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri destination;
        synchronized (FILE_NAMES) {
            displayName = uniqueMediaStoreName(resolver, relativePath, baseName, extension);
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            destination = resolver.insert(collection, values);
        }
        if (destination == null) {
            throw new IOException("Android no pudo crear el archivo de destino");
        }

        try {
            if (!preferences.edit().putString(uriKey, destination.toString()).commit()) {
                throw new IOException("Android no pudo registrar el archivo parcial.");
            }
            long bytesWritten;
            OutputStream raw = resolver.openOutputStream(destination, "w");
            if (raw == null) {
                throw new IOException("Android no pudo abrir el archivo de destino");
            }
            try (CountingOutputStream output = new CountingOutputStream(
                    control.wrap(new BufferedOutputStream(raw)))) {
                transfer(item, output);
                output.flush();
                bytesWritten = output.getCount();
            }
            if (bytesWritten <= 0) {
                throw new IOException("La plataforma devolvió un archivo vacío");
            }

            control.check();
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(destination, complete, null, null) <= 0) {
                throw new IOException("Android no pudo finalizar el archivo");
            }
            preferences.edit().remove(uriKey).commit();
            return displayName;
        } catch (Exception error) {
            try {
                resolver.delete(destination, null, null);
            } catch (Exception cleanupFailure) {
                // Keep the marker for the next launch, and never retry over an uncleared target.
                throw new IOException("Android no pudo retirar el archivo parcial.", cleanupFailure);
            }
            preferences.edit().remove(uriKey).commit();
            throw error;
        }
    }

    private String saveLegacy(MediaItem item, String baseName, String extension) throws Exception {
        File folder = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FOLDER);
        if (!folder.exists() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("No se pudo crear la carpeta de descargas.");
        }
        File temporary = File.createTempFile(".descargasocial-", ".part", folder);
        boolean complete = false;
        try {
            if (!preferences.edit().putString(pathKey, temporary.getAbsolutePath()).commit()) {
                throw new IOException("Android no pudo registrar el archivo parcial.");
            }
            long bytesWritten;
            try (CountingOutputStream output = new CountingOutputStream(
                    control.wrap(new BufferedOutputStream(new FileOutputStream(temporary))))) {
                transfer(item, output);
                output.flush();
                bytesWritten = output.getCount();
            }
            if (bytesWritten <= 0) { throw new IOException("El servidor devolvió un archivo vacío."); }
            control.check();
            File destination;
            synchronized (FILE_NAMES) {
                destination = uniqueLegacyFile(folder, baseName, extension);
                if (destination.exists() || !temporary.renameTo(destination)) {
                    throw new IOException("Android no pudo finalizar el archivo.");
                }
            }
            complete = true;
            try {
                MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()},
                        new String[]{item.getMimeType()}, null);
            } catch (RuntimeException ignored) {
                // A media-indexing issue cannot undo a successfully committed file.
            }
            return destination.getName();
        } finally {
            if (!complete && temporary.exists() && !temporary.delete()) {
                throw new IOException("Android no pudo retirar el archivo parcial.");
            }
            preferences.edit().remove(pathKey).commit();
        }
    }

    private String uniqueMediaStoreName(
            ContentResolver resolver,
            String relativePath,
            String baseName,
            String extension) {
        for (int attempt = 1; attempt < 10_000; attempt++) {
            String suffix = attempt == 1 ? "" : "_" + attempt;
            String candidate = baseName + suffix + extension;
            if (!mediaStoreNameExists(resolver, relativePath, candidate)) {
                return candidate;
            }
        }
        return baseName + "_" + System.currentTimeMillis() + extension;
    }

    private boolean mediaStoreNameExists(
            ContentResolver resolver,
            String relativePath,
            String displayName) {
        Cursor cursor = null;
        try {
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            cursor = resolver.query(
                    collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{relativePath, displayName},
                    null);
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException ignored) {
            // El proveedor resolverá una posible colisión si no permite consultar Downloads.
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private File uniqueLegacyFile(File folder, String baseName, String extension) {
        File candidate = new File(folder, baseName + extension);
        for (int attempt = 2; candidate.exists() && attempt < 10_000; attempt++) {
            candidate = new File(folder, baseName + "_" + attempt + extension);
        }
        return candidate;
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            count += length;
        }

        private long getCount() {
            return count;
        }
    }

}
