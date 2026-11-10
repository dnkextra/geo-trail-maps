package org.geotrailmaps.render;

/** The first page of a PDF opened by PdfRenderService. Sizes are in points. */
interface IPdfPage {
    int getWidth();
    int getHeight();
    /** The page region starting at ([left], [top]) points, at [scale] pixels per point. */
    Bitmap render(float left, float top, float scale, int widthPx, int heightPx);
    void close();
}
