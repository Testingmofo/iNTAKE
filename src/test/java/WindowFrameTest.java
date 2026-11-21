import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

final class WindowFrameTest {
    private static final double WIDTH = 1280;
    private static final double HEIGHT = 784;

    @Test
    void silhouetteMatchesCyberdeckCutoutGeometry() {
        List<Double> points = Main.silhouettePoints(WIDTH, HEIGHT, 0);

        assertEquals(List.of(
                0.0, 50.0,
                22.0, 28.0,
                980.0, 28.0,
                1008.0, 0.0,
                1252.0, 0.0,
                1280.0, 28.0,
                1280.0, 760.0,
                1256.0, 784.0,
                24.0, 784.0,
                0.0, 760.0), points);
    }

    @Test
    void everyVisibleResizeRegionMapsToItsExpectedDirection() {
        assertEquals(Main.ResizeMode.NORTH,
                Main.resizeModeAt(1100, 2, WIDTH, HEIGHT, false));
        assertEquals(Main.ResizeMode.WEST,
                Main.resizeModeAt(2, 300, WIDTH, HEIGHT, false));
        assertEquals(Main.ResizeMode.EAST,
                Main.resizeModeAt(1278, 300, WIDTH, HEIGHT, false));
        assertEquals(Main.ResizeMode.SOUTH,
                Main.resizeModeAt(600, 782, WIDTH, HEIGHT, false));
        assertEquals(Main.ResizeMode.SOUTH_WEST,
                Main.resizeModeAt(12, 772, WIDTH, HEIGHT, false));
        assertEquals(Main.ResizeMode.SOUTH_EAST,
                Main.resizeModeAt(1268, 772, WIDTH, HEIGHT, false));
        assertEquals(Main.ResizeMode.NORTH_WEST,
                Main.resizeModeAt(11, 39, WIDTH, HEIGHT, false));
        assertEquals(Main.ResizeMode.NORTH_EAST,
                Main.resizeModeAt(1266, 14, WIDTH, HEIGHT, false));
    }

    @Test
    void maximizedWindowCannotEnterManualResizeMode() {
        assertEquals(Main.ResizeMode.NONE,
                Main.resizeModeAt(1279, 300, WIDTH, HEIGHT, true));
    }
}
