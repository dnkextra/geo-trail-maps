package org.geotrailmaps.render;

import org.geotrailmaps.render.IPdfPage;

/** See PdfRenderService. */
interface IPdfRenderService {
    /** Opens the first page of [pdf]; throws IllegalStateException if it can't be read. */
    IPdfPage openFirstPage(in ParcelFileDescriptor pdf);
}
