package org.jabref.logic.pdf.search.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.strings.StringUtil;
import org.jabref.preferences.JabRefPreferences;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import static org.jabref.model.pdf.search.SearchFieldConstants.AUTHOR;
import static org.jabref.model.pdf.search.SearchFieldConstants.CONTENT;
import static org.jabref.model.pdf.search.SearchFieldConstants.KEY;
import static org.jabref.model.pdf.search.SearchFieldConstants.KEYWORDS;
import static org.jabref.model.pdf.search.SearchFieldConstants.PATH;
import static org.jabref.model.pdf.search.SearchFieldConstants.SUBJECT;
import static org.jabref.model.pdf.search.SearchFieldConstants.TITLE;
import static org.jabref.model.pdf.search.SearchFieldConstants.UID;

/**
 * Utility class for reading the data from LinkedFiles of a BibEntry for Lucene.
 */
public final class DocumentReader {
    private static final Log LOGGER = LogFactory.getLog(DocumentReader.class);

    private final BibEntry entry;

    /**
     * Creates a new DocumentReader using a BibEntry.
     *
     * @param bibEntry Must not be null and must have at least one LinkedFile.
     */
    public DocumentReader(BibEntry bibEntry) {
        if (bibEntry.getFiles().isEmpty()) {
            throw new IllegalStateException("There are no linked PDF files to this BibEntry!");
        }

        this.entry = bibEntry;
    }

    /**
     * Reads a LinkedFile of a BibEntry and converts it into a Lucene Document which is then returned.
     *
     * @return An Optional of a Lucene Document with the (meta)data. Can be empty if there is a problem reading the LinkedFile.
     */
    public Optional<Document> readLinkedPdf(BibDatabaseContext databaseContext, LinkedFile pdf) {
        Optional<Path> pdfPath = pdf.findIn(databaseContext, JabRefPreferences.getInstance().getFilePreferences());
        if (pdfPath.isPresent()) {
            try {
                return Optional.of(readPdfContents(pdf, pdfPath.get()));
            } catch (IOException e) {
                LOGGER.info("Could not read pdf file: " + pdf.getLink() + "!", e);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads each LinkedFile of a BibEntry and converts them into Lucene Documents which are then returned.
     *
     * @return A List of Documents with the (meta)data. Can be empty if there is a problem reading the LinkedFile.
     */
    public List<Document> readLinkedPdfs(BibDatabaseContext databaseContext) {
        List<Document> documents = new LinkedList<>();
        for (LinkedFile pdf : this.entry.getFiles()) {
            Optional<Document> document = readLinkedPdf(databaseContext, pdf);
            document.ifPresent(d -> {
                documents.add(document.get());
            });
        }
        return documents;
    }

    private Document readPdfContents(LinkedFile pdf, Path resolvedPdfPath) throws IOException {
        try (PDDocument pdfDocument = PDDocument.load(resolvedPdfPath.toFile())) {
            Document newDocument = new Document();
            addIdentifiers(newDocument);
            addContentIfNotEmpty(pdfDocument, newDocument);
            addMetaData(pdfDocument, newDocument, pdf.getLink());
            return newDocument;
        }
    }

    private void addMetaData(PDDocument pdfDocument, Document newDocument, String path) {
        PDDocumentInformation info = pdfDocument.getDocumentInformation();
        addStringField(newDocument, AUTHOR, info.getAuthor());
        addStringField(newDocument, TITLE, info.getTitle());
        addStringField(newDocument, SUBJECT, info.getSubject());
        addTextField(newDocument, KEYWORDS, info.getKeywords());
        addTextField(newDocument, PATH, path); // @todo make sure this is the path as it is put in the bib file (not after findIn) to make sure updating works when the library is moved
    }

    private void addTextField(Document newDocument, String field, String value) {
        if (!isValidField(value)) {
            return;
        }
        newDocument.add(new TextField(field, value, Field.Store.YES));
    }

    private void addStringField(Document newDocument, String field, String value) {
        if (!isValidField(value)) {
            return;
        }
        newDocument.add(new StringField(field, value, Field.Store.YES));
    }

    private boolean isValidField(String value) {
        return !(StringUtil.isNullOrEmpty(value));
    }

    private void addContentIfNotEmpty(PDDocument pdfDocument, Document newDocument) {
        try {
            PDFTextStripper pdfTextStripper = new PDFTextStripper();
            pdfTextStripper.setLineSeparator("\n");

            String pdfContent = pdfTextStripper.getText(pdfDocument);
            if (StringUtil.isNotBlank(pdfContent)) {
                newDocument.add(new TextField(CONTENT, pdfContent, Field.Store.YES));
            }
        } catch (IOException e) {
            LOGGER.info("Could not read contents of PDF document " + pdfDocument.toString(), e);
        }
    }

    private void addIdentifiers(Document newDocument) {
        newDocument.add(new StoredField(UID, this.entry.getId()));
        if (this.entry.getCitationKey().isPresent()) {
            newDocument.add(new StringField(KEY, this.entry.getCitationKey().get(), Field.Store.YES));
        }
    }
}