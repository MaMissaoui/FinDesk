package de.findesk.document

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.printing.PDFPageable
import java.awt.print.PrinterJob

/**
 * Prints a prepared [PDDocument]. Returns false when the user cancelled the print dialog; an exception
 * means printing failed and the caller shows an error. Tests replace this with a fake — the real one is
 * never called there, since it would open the system print dialog.
 */
interface TaxPrinter {
    fun print(document: PDDocument, jobName: String): Boolean
}

/**
 * Prints through the system print dialog, straight from the in-memory document via PDFBox's
 * [PDFPageable]: no temporary PDF file is written for printing.
 */
class SystemTaxPrinter : TaxPrinter {
    override fun print(document: PDDocument, jobName: String): Boolean {
        val job = PrinterJob.getPrinterJob()
        job.jobName = jobName
        job.setPageable(PDFPageable(document))
        return if (job.printDialog()) {
            job.print()
            true
        } else {
            false
        }
    }
}
