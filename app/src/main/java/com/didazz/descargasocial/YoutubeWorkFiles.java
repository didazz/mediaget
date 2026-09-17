// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.io.File;
import java.io.IOException;
/** Only the three exact staging files owned by a YouTube task may be removed. */
final class YoutubeWorkFiles {
    static File folder(File cache, String taskId) throws IOException {
        if (!taskId.matches("[A-Za-z0-9-]{1,64}")) { throw new StorageException("TEMP_ID",Messages.ref("error_91")); }
        return new File(cache.getCanonicalFile(),"youtube-"+taskId);
    }
    static void clean(File cache,String path) throws IOException {
        if(path==null) { return; }
        File directory=new File(path);
        File canonicalCache=cache.getCanonicalFile();
        File canonicalDirectory=directory.getCanonicalFile();
        // The trusted cache root can have an Android-provided alias. The task directory itself
        // must still be the exact direct child, never a link to another task or an outside path.
        if(!directory.getName().matches("youtube-[A-Za-z0-9-]{1,64}")
                || !canonicalDirectory.equals(new File(canonicalCache,directory.getName()))) {
            throw new StorageException("TEMP_PATH",Messages.ref("error_92"));
        }
        directory=canonicalDirectory;
        if(!directory.exists()) { return; }
        File[] files=directory.listFiles();
        if(files==null) { throw new StorageException("TEMP_READ",Messages.ref("error_93")); }
        for(File file:files) {
            if(!file.getName().matches("(video|audio)\\.part|muxed\\.mp4")
                    || !file.getCanonicalFile().equals(new File(canonicalDirectory,file.getName()))
                    || !file.isFile()) {
                throw new StorageException("TEMP_CONTENT",Messages.ref("error_94"));
            }
        }
        for(File file:files) {
            if(file.exists() && !file.delete()) { throw new StorageException("TEMP_DELETE",Messages.ref("error_95")); }
        }
        if(!directory.delete() && directory.exists()) { throw new StorageException("TEMP_DELETE",Messages.ref("error_96")); }
    }
}
