package org.tagmod.launcher;

import java.io.File;
import java.util.Locale;

final class Book implements LibraryView.Row {
    final File file;
    final String title;
    final String author;
    long lastOpened;

    Book(File file, String title, String author) {
        this.file = file;
        this.title = title;
        this.author = author;
    }

    String extension() {
        return Library.extension(file.getName());
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String subtitle() {
        String format = extension().toUpperCase(Locale.ROOT);
        return author.length() > 0 ? author + "  ·  " + format : format;
    }
}
