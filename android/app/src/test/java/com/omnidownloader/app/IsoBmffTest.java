package com.omnidownloader.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class IsoBmffTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(8 + payload.length);
        bb.put(type.getBytes(StandardCharsets.ISO_8859_1));
        bb.put(payload);
        return bb.array();
    }

    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p);
        return out.toByteArray();
    }

    private static byte[] fullBoxTimestamps(int version, long ctime, long mtime, int tailBytes) {
        int size = (version == 0 ? 12 : 20) + tailBytes;
        ByteBuffer bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) version);
        bb.put((byte) 0);
        bb.put((byte) 0);
        bb.put((byte) 0);
        if (version == 0) {
            bb.putInt((int) ctime);
            bb.putInt((int) mtime);
        } else {
            bb.putLong(ctime);
            bb.putLong(mtime);
        }
        return bb.array();
    }

    @Test
    public void sniffsMp4WebmAndRejectsBogusBoxSizes() throws Exception {
        File mp4 = tmp.newFile("sample.mp4");
        try (FileOutputStream fos = new FileOutputStream(mp4)) {
            fos.write(box("ftyp", "isom\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1)));
        }
        assertEquals(IsoBmff.ContainerType.MP4, IsoBmff.sniff(mp4));

        File webm = tmp.newFile("sample.webm");
        try (FileOutputStream fos = new FileOutputStream(webm)) {
            fos.write(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x84, 0x42, (byte) 0x86, (byte) 0x81});
        }
        assertEquals(IsoBmff.ContainerType.WEBM, IsoBmff.sniff(webm));

        File bogus = tmp.newFile("bogus.bin");
        try (FileOutputStream fos = new FileOutputStream(bogus)) {
            fos.write(new byte[]{0, 0, 0, 3, 'f', 't', 'y', 'p', 1, 2, 3, 4});
        }
        assertEquals(IsoBmff.ContainerType.UNKNOWN, IsoBmff.sniff(bogus));
    }

    @Test
    public void sanitizesMp4MvhdTkhdMdhdAndScrubsUdtaMetaInPlace() throws Exception {
        byte[] mvhd = box("mvhd", fullBoxTimestamps(0, 1234L, 5678L, 16));
        byte[] tkhd = box("tkhd", fullBoxTimestamps(0, 1111L, 2222L, 16));
        byte[] mdhd = box("mdhd", fullBoxTimestamps(0, 3333L, 4444L, 12));
        byte[] mdia = box("mdia", mdhd);
        byte[] trakUdta = box("udta", "tracking-tag-123".getBytes(StandardCharsets.US_ASCII));
        byte[] trak = box("trak", concat(tkhd, mdia, trakUdta));
        byte[] moovMeta = box("meta", "encoder=Lavf60.0".getBytes(StandardCharsets.US_ASCII));
        byte[] moov = box("moov", concat(mvhd, trak, moovMeta));
        byte[] ftyp = box("ftyp", "isom\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        byte[] mdat = box("mdat", new byte[]{10, 20, 30, 40, 50, 60});

        byte[] origBytes = concat(ftyp, moov, mdat);
        File input = tmp.newFile("in.mp4");
        File output = tmp.newFile("out.mp4");
        try (FileOutputStream fos = new FileOutputStream(input)) {
            fos.write(origBytes);
        }

        IsoBmff.CopyResult copyRes = IsoBmff.copyAndInjectEntropy(input, output, IsoBmff.ContainerType.MP4);
        assertTrue(copyRes.success);
        assertEquals(origBytes.length + 64, output.length());
        assertTrue(IsoBmff.sanitizeMp4Atoms(output));

        byte[] outBytes = new byte[(int) output.length()];
        try (FileInputStream fis = new FileInputStream(output)) {
            assertEquals(outBytes.length, fis.read(outBytes));
        }
        String asAscii = new String(outBytes, StandardCharsets.ISO_8859_1);
        // udta and meta tracking strings were scrubbed and replaced with 'free'
        assertFalse(asAscii.contains("tracking-tag-123"));
        assertFalse(asAscii.contains("encoder=Lavf60.0"));
        assertFalse(asAscii.contains("udta"));
        assertFalse(asAscii.contains("meta"));
        // mdat bytes at original offset were not shifted or altered
        int mdatOffset = ftyp.length + moov.length;
        for (int i = 0; i < mdat.length; i++) {
            assertEquals(mdat[i], outBytes[mdatOffset + i]);
        }
        // Trailing 64 bytes is a 'free' box
        assertEquals("free", new String(outBytes, origBytes.length + 4, 4, StandardCharsets.ISO_8859_1));
    }

    @Test
    public void injectsWebmVoidInsideFiniteSegment() throws Exception {
        // EBML header: ID 0x1A45DFA3, size 0x84 (4 bytes payload)
        byte[] ebmlHeader = new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x84, 0x42, (byte) 0x86, (byte) 0x81, 0x01};
        // Segment: ID 0x18538067, 4-byte VINT size = 10 (0x10 00 00 0A)
        byte[] segPayload = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        byte[] segHeader = new byte[]{0x18, 0x53, (byte) 0x80, 0x67, 0x10, 0x00, 0x00, 0x0A};

        File inWebm = tmp.newFile("in.webm");
        File outWebm = tmp.newFile("out.webm");
        try (FileOutputStream fos = new FileOutputStream(inWebm)) {
            fos.write(concat(ebmlHeader, segHeader, segPayload));
        }

        IsoBmff.CopyResult res = IsoBmff.copyAndInjectEntropy(inWebm, outWebm, IsoBmff.ContainerType.WEBM);
        assertTrue(res.success);
        assertNotEquals(res.inputSha256, res.outputSha256);
        assertEquals(inWebm.length() + 64, outWebm.length());

        byte[] outBytes = new byte[(int) outWebm.length()];
        try (FileInputStream fis = new FileInputStream(outWebm)) {
            assertEquals(outBytes.length, fis.read(outBytes));
        }
        // Segment size header (at offset 9 + 4 = 13) should now encode 10 + 64 = 74 (0x4A)
        int segSizeOff = ebmlHeader.length + 4;
        assertEquals(0x10, outBytes[segSizeOff] & 0xFF);
        assertEquals(0x00, outBytes[segSizeOff + 1] & 0xFF);
        assertEquals(0x00, outBytes[segSizeOff + 2] & 0xFF);
        assertEquals(74, outBytes[segSizeOff + 3] & 0xFF);

        // Trailing 64 bytes must start with EBML Void (0xEC 0xBE)
        int voidOff = (int) inWebm.length();
        assertEquals((byte) 0xEC, outBytes[voidOff]);
        assertEquals((byte) 0xBE, outBytes[voidOff + 1]);
    }
}
