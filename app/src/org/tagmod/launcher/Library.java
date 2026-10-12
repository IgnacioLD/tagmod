package org.tagmod.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.xmlpull.v1.XmlPullParser;
import android.util.Xml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds books on storage, reads their metadata and remembers when each was last opened. */
final class Library {
    static final Set<String> EXTENSIONS = new HashSet<String>();
    static {
        Collections.addAll(EXTENSIONS, "epub", "pdf", "djvu", "cbz", "fb2", "fb2.zip", "fb3",
                "fbz", "mobi", "azw", "azw3", "prc", "pdb", "txt", "rtf", "doc", "docx", "odt", "chm");
    }

    private static final int MAX_DEPTH = 8;
    private static final String CACHE_FILE = "metadata.tsv";

    private final Context context;
    private final SharedPreferences recents;
    /** "path\tmtime" -> {title, author} */
    private final Map<String, String[]> metadata = new HashMap<String, String[]>();
    private boolean metadataDirty;

    Library(Context context) {
        this.context = context;
        this.recents = context.getSharedPreferences("recents", Context.MODE_PRIVATE);
        loadMetadata();
    }

    static String extension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return "fb2.zip";
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    static List<File> roots() {
        List<File> roots = new ArrayList<File>();
        roots.add(Environment.getExternalStorageDirectory());
        String secondary = System.getenv("SECONDARY_STORAGE");
        if (secondary != null) {
            for (String path : secondary.split(":")) {
                if (path.length() > 0) roots.add(new File(path));
            }
        }
        return roots;
    }

    /** Scans storage. Slow; call off the main thread. */
    List<Book> scan() {
        List<File> files = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        for (File root : roots()) {
            walk(root, 0, files, seen);
        }
        List<Book> books = new ArrayList<Book>();
        for (File f : files) {
            String[] meta = metadataFor(f);
            Book b = new Book(f, meta[0], meta[1]);
            b.lastOpened = recents.getLong(f.getAbsolutePath(), 0);
            books.add(b);
        }
        if (metadataDirty) saveMetadata();
        sort(books);
        return books;
    }

    static void sort(List<Book> books) {
        final Collator collator = Collator.getInstance(new Locale("es", "ES"));
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(books, new Comparator<Book>() {
            @Override
            public int compare(Book a, Book b) {
                if (a.lastOpened != b.lastOpened) return a.lastOpened > b.lastOpened ? -1 : 1;
                return collator.compare(a.title, b.title);
            }
        });
    }

    void markOpened(Book book) {
        book.lastOpened = System.currentTimeMillis();
        // last_title/last_author feed the sleep panel ("Leyendo").
        recents.edit().putLong(book.file.getAbsolutePath(), book.lastOpened)
                .putString("last_title", book.title).putString("last_author", book.author).apply();
    }

    private static void walk(File dir, int depth, List<File> out, Set<String> seen) {
        if (depth > MAX_DEPTH) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                // TagMod/ holds the panel's settings (panel.txt, servicios.txt), not books.
                if (depth == 0 && (name.equals("Android") || name.equals("LOST.DIR") || name.equals("TagMod"))) continue;
                walk(f, depth + 1, out, seen);
            } else if (EXTENSIONS.contains(extension(name))) {
                String key;
                try {
                    key = f.getCanonicalPath();
                } catch (Exception e) {
                    key = f.getAbsolutePath();
                }
                if (seen.add(key)) out.add(f);
            }
        }
    }

    private String[] metadataFor(File f) {
        String key = f.getAbsolutePath() + "\t" + f.lastModified();
        String[] meta = metadata.get(key);
        if (meta != null) return meta;
        meta = new String[] {titleFromName(f.getName()), ""};
        if (extension(f.getName()).equals("epub")) {
            String[] epub = readEpubMetadata(f);
            if (epub != null) {
                if (epub[0] != null && epub[0].trim().length() > 0) meta[0] = epub[0].trim();
                if (epub[1] != null) meta[1] = epub[1].trim();
            }
        }
        metadata.put(key, meta);
        metadataDirty = true;
        return meta;
    }

    static String titleFromName(String name) {
        String ext = extension(name);
        String base = ext.length() > 0 ? name.substring(0, name.length() - ext.length() - 1) : name;
        return base.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Returns {title, author} from the EPUB's OPF package document, or null. */
    private static String[] readEpubMetadata(File f) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(f);
            ZipEntry container = zip.getEntry("META-INF/container.xml");
            if (container == null) return null;
            String opfPath = null;
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            InputStream in = zip.getInputStream(container);
            try {
                p.setInput(in, null);
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG && p.getName().equals("rootfile")) {
                        opfPath = p.getAttributeValue(null, "full-path");
                        break;
                    }
                }
            } finally {
                in.close();
            }
            if (opfPath == null) return null;
            ZipEntry opf = zip.getEntry(opfPath);
            if (opf == null) return null;
            String title = null, author = null;
            p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            in = zip.getInputStream(opf);
            try {
                p.setInput(in, null);
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.END_TAG && p.getName().equals("metadata")) break;
                    if (ev != XmlPullParser.START_TAG) continue;
                    if (title == null && p.getName().equals("title")) title = p.nextText();
                    else if (author == null && p.getName().equals("creator")) author = p.nextText();
                }
            } finally {
                in.close();
            }
            return new String[] {title, author};
        } catch (Exception e) {
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void loadMetadata() {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length == 4) {
                    metadata.put(parts[0] + "\t" + parts[1], new String[] {parts[2], parts[3]});
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void saveMetadata() {
        File tmp = new File(context.getFilesDir(), CACHE_FILE + ".tmp");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            for (Map.Entry<String, String[]> e : metadata.entrySet()) {
                String[] m = e.getValue();
                w.write(e.getKey() + "\t" + clean(m[0]) + "\t" + clean(m[1]) + "\n");
            }
            w.close();
            w = null;
            if (tmp.renameTo(new File(context.getFilesDir(), CACHE_FILE))) metadataDirty = false;
        } catch (Exception ignored) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
