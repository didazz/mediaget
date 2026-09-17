// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.io.IOException;
import java.util.regex.*;
final class YoutubeRange {
    private static final Pattern RANGE=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)",Pattern.CASE_INSENSITIVE);
    static long expectedBytes(int status,String range,long offset,long end,long total) throws IOException {
        if(total<=0 || offset<0 || end<offset || end>=total) {
            throw new IOException("El rango solicitado a YouTube no es válido.");
        }
        if(status==200 && offset==0) { return total; }
        if(status!=206 || range==null) { throw new IOException("YouTube no respetó el rango solicitado."); }
        Matcher m=RANGE.matcher(range.trim());
        try {
            if(!m.matches() || Long.parseLong(m.group(1))!=offset || Long.parseLong(m.group(2))!=end
                    || Long.parseLong(m.group(3))!=total || offset<0 || end<offset || end>=total) {
                throw new IOException("YouTube devolvió un rango incoherente.");
            }
            return end-offset+1;
        } catch(NumberFormatException invalid) { throw new IOException("YouTube devolvió un rango inválido."); }
    }
}
