import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

// Uses the same ZXing core as Android against actual GPUI native frame readback.
// Compile/run with JDK 25 and ZXing core on --class-path.
class VerifyPairingQr {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        String expected = Files.readString(directory.resolve("expected-qr.txt"));
        for (String theme : new String[]{"light", "dark"}) {
          for (int rem : new int[]{12, 16, 20}) {
            BufferedImage image = ImageIO.read(directory.resolve(theme + "-" + rem + ".png").toFile());
            int width = image.getWidth(), height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(width, height, pixels)))).getText();
            if (!expected.equals(decoded)) throw new AssertionError(theme + ": invitation mismatch");
            System.out.println(theme + " / rem=" + rem + ": native QR decoded; exact invitation match (" + decoded.length() + " chars)");
          }
        }
    }
}
