package studio.otherstuff.ambulando.app.browser;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Disposable bytes in the test APK's standalone process; deliberately needs no target-app runtime classes. */
public final class BrowserTreeFixtureProvider extends ContentProvider {
    public static final String AUTHORITY = "studio.otherstuff.ambulando.browser.fixture";
    @Override public android.os.Bundle call(String method, String target, android.os.Bundle extras) {
        if ("select".equals(method)) getContext().grantUriPermission(target, DocumentsContract.buildTreeDocumentUri(AUTHORITY, "fixture"), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return android.os.Bundle.EMPTY;
    }
    @Override public boolean onCreate() { return true; }
    private String content(String id) {
        if (id.equals("fixture/root.txt")) return "root-bytes";
        if (id.equals("fixture/sub/child.txt")) return "child-bytes";
        return null;
    }
    @Override public String getType(Uri uri) {
        return content(DocumentsContract.getDocumentId(uri)) == null ? DocumentsContract.Document.MIME_TYPE_DIR : "text/plain";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection != null ? projection : new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        if ("/root".equals(uri.getPath())) return new MatrixCursor(projection != null ? projection : new String[] {"root_id"});
        String id = DocumentsContract.getDocumentId(uri);
        String[] ids;
        if ("children".equals(uri.getLastPathSegment())) {
            if (id.equals("fixture")) ids = new String[] {"fixture/root.txt", "fixture/sub"};
            else if (id.equals("fixture/sub")) ids = new String[] {"fixture/sub/child.txt"};
            else ids = new String[0];
        } else ids = new String[] {id};
        MatrixCursor cursor = new MatrixCursor(columns);
        for (String item : ids) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (columns[i].equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) row[i] = item;
                else if (columns[i].equals(OpenableColumns.DISPLAY_NAME)) row[i] = item.substring(item.lastIndexOf('/') + 1);
                else if (columns[i].equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) row[i] = content(item) == null ? DocumentsContract.Document.MIME_TYPE_DIR : "text/plain";
                else if (columns[i].equals(OpenableColumns.SIZE)) row[i] = content(item) == null ? 0 : content(item).length();
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    private String idForFile(Uri uri) { return DocumentsContract.getDocumentId(uri).replace('/', '_'); }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String data = content(DocumentsContract.getDocumentId(uri));
        if (!mode.equals("r") || data == null) throw new FileNotFoundException();
        try {
            java.io.File file = new java.io.File(getContext().getCacheDir(), idForFile(uri));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(data.getBytes(StandardCharsets.UTF_8)); }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
}
