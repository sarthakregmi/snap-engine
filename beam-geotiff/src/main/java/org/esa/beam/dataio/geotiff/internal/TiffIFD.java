package org.esa.beam.dataio.geotiff.internal;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.util.Guardian;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.geotiff.GeoTIFFMetadata;

import javax.imageio.stream.ImageOutputStream;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.util.ArrayList;

/**
 * A TIFF IFD implementation for the GeoTIFF format.
 *
 * @author Marco Peters
 * @author Sabine Embacher
 * @author Norman Fomferra
 * @version $Revision: 2932 $ $Date: 2008-08-28 16:43:48 +0200 (Do, 28 Aug 2008) $
 */
public class TiffIFD {

    private final TiffDirectoryEntrySet entrySet;
    private static final int BYTES_FOR_NEXT_IFD_OFFSET = 4;
    private static final int BYTES_FOR_NUMBER_OF_ENTRIES = 2;
    private int maxElemSizeBandDataType;

    public TiffIFD(final Product product) {
        entrySet = new TiffDirectoryEntrySet();
        initEntrys(product);
    }

    public void write(final ImageOutputStream ios, final long ifdOffset, final long nextIfdOffset) throws IOException {
        Guardian.assertGreaterThan("ifdOffset", ifdOffset, -1);
        computeOffsets(ifdOffset);
        ios.seek(ifdOffset);
        final TiffDirectoryEntry[] entries = entrySet.getEntries();
        new TiffShort(entries.length).write(ios);
        long entryPosition = ios.getStreamPosition();
        for (TiffDirectoryEntry entry : entries) {
            ios.seek(entryPosition);
            entry.write(ios);
            entryPosition += TiffDirectoryEntry.BYTES_PER_ENTRY;
        }
        writeNextIfdOffset(ios, ifdOffset, nextIfdOffset);
    }

    private void writeNextIfdOffset(final ImageOutputStream ios, final long ifdOffset, final long nextIfdOffset) throws
            IOException {
        ios.seek(getPosForNextIfdOffset(ifdOffset));
        new TiffLong(nextIfdOffset).write(ios);
    }

    private long getPosForNextIfdOffset(final long ifdOffset) {
        return ifdOffset + getRequiredIfdSize() - 4;
    }

    public TiffDirectoryEntry getEntry(final TiffShort tag) {
        return entrySet.getEntry(tag);
    }

    public long getRequiredIfdSize() {
        final TiffDirectoryEntry[] entries = entrySet.getEntries();
        return BYTES_FOR_NUMBER_OF_ENTRIES + entries.length * TiffDirectoryEntry.BYTES_PER_ENTRY + BYTES_FOR_NEXT_IFD_OFFSET;
    }

    public long getRequiredReferencedValuesSize() {
        final TiffDirectoryEntry[] entries = entrySet.getEntries();
        long size = 0;
        for (final TiffDirectoryEntry entry : entries) {
            if (entry.mustValuesBeReferenced()) {
                size += entry.getValuesSizeInBytes();
            }
        }
        return size;
    }

    public long getRequiredSizeForStrips() {
        final TiffLong[] counts = (TiffLong[]) getEntry(TiffTag.STRIP_BYTE_COUNTS).getValues();
        long size = 0;
        for (TiffLong count : counts) {
            size += count.getValue();
        }
        return size;
    }

    public long getRequiredEntireSize() {
        return getRequiredIfdSize() + getRequiredReferencedValuesSize() + getRequiredSizeForStrips();
    }

    private void computeOffsets(final long ifdOffset) {
        final TiffDirectoryEntry[] entries = entrySet.getEntries();
        long valuesOffset = computeStartOffsetForValues(entries.length, ifdOffset);
        for (final TiffDirectoryEntry entry : entries) {
            if (entry.mustValuesBeReferenced()) {
                entry.setValuesOffset(valuesOffset);
                valuesOffset += entry.getValuesSizeInBytes();
            }
        }
        moveStripsTo(valuesOffset);
    }

    private void moveStripsTo(final long stripsStart) {
        final TiffLong[] values = (TiffLong[]) getEntry(TiffTag.STRIP_OFFSETS).getValues();
        for (int i = 0; i < values.length; i++) {
            final long oldValue = values[i].getValue();
            final long newValue = oldValue + stripsStart;
            values[i] = new TiffLong(newValue);
        }
    }

    private long computeStartOffsetForValues(final int numEntries, final long ifdOffset) {
        final short bytesPerEntry = TiffDirectoryEntry.BYTES_PER_ENTRY;
        final int bytesForEntries = numEntries * bytesPerEntry;
        return ifdOffset + BYTES_FOR_NUMBER_OF_ENTRIES + bytesForEntries + BYTES_FOR_NEXT_IFD_OFFSET;
    }

    private void setEntry(final TiffDirectoryEntry entry) {
        entrySet.set(entry);
    }

    public int getBandDataType() {
        return maxElemSizeBandDataType;
    }

    private void initEntrys(final Product product) {
        maxElemSizeBandDataType = getMaxElemSizeBandDataType(product.getBands());
        final int width = product.getSceneRasterWidth();
        final int height = product.getSceneRasterHeight();

        setEntry(new TiffDirectoryEntry(TiffTag.IMAGE_WIDTH, new TiffLong(width)));
        setEntry(new TiffDirectoryEntry(TiffTag.IMAGE_LENGTH, new TiffLong(height)));
        setEntry(new TiffDirectoryEntry(TiffTag.BITS_PER_SAMPLE, calculateBitsPerSample(product)));
        setEntry(new TiffDirectoryEntry(TiffTag.COMPRESSION, new TiffShort(1)));
        setEntry(new TiffDirectoryEntry(TiffTag.PHOTOMETRIC_INTERPRETATION, TiffCode.PHOTOMETRIC_BLACK_IS_ZERO));
        setEntry(new TiffDirectoryEntry(TiffTag.IMAGE_DESCRIPTION, new TiffAscii(product.getName())));
        setEntry(new TiffDirectoryEntry(TiffTag.SAMPLES_PER_PIXEL, new TiffShort(product.getNumBands())));

        setEntry(new TiffDirectoryEntry(TiffTag.STRIP_OFFSETS, calculateStripOffsets()));
        setEntry(new TiffDirectoryEntry(TiffTag.ROWS_PER_STRIP, new TiffLong(height)));
        setEntry(new TiffDirectoryEntry(TiffTag.STRIP_BYTE_COUNTS, calculateStripByteCounts()));

        setEntry(new TiffDirectoryEntry(TiffTag.X_RESOLUTION, new TiffRational(1, 1)));
        setEntry(new TiffDirectoryEntry(TiffTag.Y_RESOLUTION, new TiffRational(1, 1)));
        setEntry(new TiffDirectoryEntry(TiffTag.RESOLUTION_UNIT, new TiffShort(1)));
        setEntry(new TiffDirectoryEntry(TiffTag.PLANAR_CONFIGURATION, TiffCode.PLANAR_CONFIG_PLANAR));
        setEntry(new TiffDirectoryEntry(TiffTag.SAMPLE_FORMAT, calculateSampleFormat(product)));
        setEntry(new TiffDirectoryEntry(TiffTag.BEAM_METADATA, getBeamMetadata(product)));

        addGeoTiffTags(product);
    }

    private TiffAscii getBeamMetadata(final Product product) {
        final BeamMetadata.Metadata metadata = BeamMetadata.createMetadata(product);
        return new TiffAscii(metadata.getAsString());
    }

    private void addGeoTiffTags(final Product product) {
        final GeoTIFFMetadata geoTIFFMetadata = ProductUtils.createGeoTIFFMetadata(product);
        if (geoTIFFMetadata == null) {
            return;
        }
        // for debug purpose
//        final PrintWriter writer = new PrintWriter(System.out);
//        geoTIFFMetadata.dump(writer);
//        writer.close();
        final int numEntries = geoTIFFMetadata.getNumGeoKeyEntries();
        final TiffShort[] directoryTagValues = new TiffShort[numEntries * 4];
        final ArrayList<TiffDouble> doubleValues = new ArrayList<TiffDouble>();
        final ArrayList<String> asciiValues = new ArrayList<String>();
        for (int i = 0; i < numEntries; i++) {
            final GeoTIFFMetadata.KeyEntry entry = geoTIFFMetadata.getGeoKeyEntryAt(i);
            final int[] data = entry.getData();
            for (int j = 0; j < data.length; j++) {
                directoryTagValues[i * 4 + j] = new TiffShort(data[j]);
            }
            if (data[1] == TiffTag.GeoDoubleParamsTag.getValue()) {
                directoryTagValues[i * 4 + 3] = new TiffShort(doubleValues.size());
                final double[] geoDoubleParams = geoTIFFMetadata.getGeoDoubleParams(data[0]);
                for (double geoDoubleParam : geoDoubleParams) {
                    doubleValues.add(new TiffDouble(geoDoubleParam));
                }
            }
            if (data[1] == TiffTag.GeoAsciiParamsTag.getValue()) {
                int sizeInBytes = 0;
                for (String asciiValue : asciiValues) {
                    sizeInBytes = asciiValue.length() + 1;
                }
                directoryTagValues[i * 4 + 3] = new TiffShort(sizeInBytes);
                asciiValues.add(geoTIFFMetadata.getGeoAsciiParam(data[0]));
            }
        }
        setEntry(new TiffDirectoryEntry(TiffTag.GeoKeyDirectoryTag, directoryTagValues));
        if (!doubleValues.isEmpty()) {
            final TiffDouble[] tiffDoubles = doubleValues.toArray(new TiffDouble[doubleValues.size()]);
            setEntry(new TiffDirectoryEntry(TiffTag.GeoDoubleParamsTag, tiffDoubles));
        }
        if (!asciiValues.isEmpty()) {
            final String[] tiffAsciies = asciiValues.toArray(new String[asciiValues.size()]);
            setEntry(new TiffDirectoryEntry(TiffTag.GeoAsciiParamsTag, new GeoTiffAscii(tiffAsciies)));
        }
        double[] modelTransformation = geoTIFFMetadata.getModelTransformation();
        if (!isZeroArray(modelTransformation)) {
            setEntry(new TiffDirectoryEntry(TiffTag.ModelTransformationTag, toTiffDoubles(modelTransformation)));
        } else {
            double[] modelPixelScale = geoTIFFMetadata.getModelPixelScale();
            if (!isZeroArray(modelPixelScale)) {
                setEntry(new TiffDirectoryEntry(TiffTag.ModelPixelScaleTag, toTiffDoubles(modelPixelScale)));
            }
            final int numModelTiePoints = geoTIFFMetadata.getNumModelTiePoints();
            if (numModelTiePoints > 0) {
                final TiffDouble[] tiePoints = new TiffDouble[numModelTiePoints * 6];
                for (int i = 0; i < numModelTiePoints; i++) {
                    final GeoTIFFMetadata.TiePoint modelTiePoint = geoTIFFMetadata.getModelTiePointAt(i);
                    final double[] data = modelTiePoint.getData();
                    for (int j = 0; j < data.length; j++) {
                        tiePoints[i * 6 + j] = new TiffDouble(data[j]);
                    }
                }
                setEntry(new TiffDirectoryEntry(TiffTag.ModelTiepointTag, tiePoints));
            }
        }
    }

    private static TiffDouble[] toTiffDoubles(double[] a) {
        final TiffDouble[] td = new TiffDouble[a.length];
        for (int i = 0; i < a.length; i++) {
            td[i] = new TiffDouble(a[i]);
        }
        return td;
    }

    private static boolean isZeroArray(double[] a) {
        for (double v : a) {
            if (v != 0.0) {
                return false;
            }
        }
        return true;
    }

    static int getMaxElemSizeBandDataType(final Band[] bands) {
            int maxSignedIntType = -1;
            int maxUnsignedIntType = -1;
            int maxFloatType = -1;
            for (Band band : bands) {
                int dt = band.getDataType();
                if (ProductData.isIntType(dt)) {
                    if (ProductData.isUIntType(dt)) {
                        maxUnsignedIntType = Math.max(maxUnsignedIntType, dt);
                    } else {
                        maxSignedIntType = Math.max(maxSignedIntType, dt);
                    }
                }
                if (ProductData.isFloatingPointType(dt)) {
                    maxFloatType = Math.max(maxFloatType, dt);
                }
            }

            if (maxFloatType == ProductData.TYPE_FLOAT64) {
                return ProductData.TYPE_FLOAT64;
            }

            if (maxFloatType != -1) {
                if (maxSignedIntType > ProductData.TYPE_INT16 || maxUnsignedIntType > ProductData.TYPE_UINT16) {
                    return ProductData.TYPE_FLOAT64;
                } else {
                    return ProductData.TYPE_FLOAT32;
                }
            }

            if (maxUnsignedIntType != -1) {
                if (maxSignedIntType == -1) {
                    return maxUnsignedIntType;
                }
                if (ProductData.getElemSize(maxUnsignedIntType) >= ProductData.getElemSize(maxSignedIntType)) {
                    int returnType = maxUnsignedIntType - 10 + 1;
                    if (returnType > 12) {
                        return ProductData.TYPE_FLOAT64;
                    } else {
                        return returnType;
                    }
                }
            }

            if (maxSignedIntType != -1) {
                return maxSignedIntType;
            }

            return DataBuffer.TYPE_UNDEFINED;
    }

    private TiffShort[] calculateSampleFormat(final Product product) {
        int dataType = getBandDataType();
        TiffShort sampleFormat;
        if (ProductData.isUIntType(dataType)) {
            sampleFormat = TiffCode.SAMPLE_FORMAT_UINT;
        } else if (ProductData.isIntType(dataType)) {
            sampleFormat = TiffCode.SAMPLE_FORMAT_INT;
        } else {
            sampleFormat = TiffCode.SAMPLE_FORMAT_FLOAT;
        }

        final TiffShort[] tiffValues = new TiffShort[product.getNumBands()];
        for (int i = 0; i < tiffValues.length; i++) {
            tiffValues[i] = sampleFormat;
        }

        return tiffValues;
    }

    private TiffLong[] calculateStripByteCounts() {
        TiffValue[] bitsPerSample = getBitsPerSampleValues();
        final TiffLong[] tiffValues = new TiffLong[bitsPerSample.length];
        for (int i = 0; i < tiffValues.length; i++) {
            long byteCount = getByteCount(bitsPerSample, i);
            tiffValues[i] = new TiffLong(byteCount);
        }
        return tiffValues;
    }

    private TiffLong[] calculateStripOffsets() {
        TiffValue[] bitsPerSample = getBitsPerSampleValues();
        final TiffLong[] tiffValues = new TiffLong[bitsPerSample.length];
        long offset = 0;
        for (int i = 0; i < tiffValues.length; i++) {
            tiffValues[i] = new TiffLong(offset);
            long byteCount = getByteCount(bitsPerSample, i);
            offset += byteCount;
        }
        return tiffValues;
    }

    private long getByteCount(TiffValue[] bitsPerSample, int i) {
        long bytesPerSample = ((TiffShort) bitsPerSample[i]).getValue() / 8;
        long byteCount = getWidth() * getHeight() * bytesPerSample;
        return byteCount;
    }

    private TiffShort[] calculateBitsPerSample(final Product product) {
        int dataType = getBandDataType();
        int elemSize = ProductData.getElemSize(dataType);
        final TiffShort[] tiffValues = new TiffShort[product.getNumBands()];
        for (int i = 0; i < tiffValues.length; i++) {
            tiffValues[i] = new TiffShort(8 * elemSize);
        }
        return tiffValues;
    }

    private TiffValue[] getBitsPerSampleValues() {
        return getEntry(TiffTag.BITS_PER_SAMPLE).getValues();
    }

    private long getHeight() {
        return ((TiffLong) getEntry(TiffTag.IMAGE_LENGTH).getValues()[0]).getValue();
    }

    private long getWidth() {
        return ((TiffLong) getEntry(TiffTag.IMAGE_WIDTH).getValues()[0]).getValue();
    }
}
