// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.io.IOException;
/** App-generated storage errors with stable codes and messages safe to show/copy. */
final class StorageException extends IOException {
    private static final long serialVersionUID=1L;
    final String code;
    StorageException(String code,String message){super(message);this.code=code;}
    StorageException(String code,String message,Throwable cause){super(message,cause);this.code=code;}
}
