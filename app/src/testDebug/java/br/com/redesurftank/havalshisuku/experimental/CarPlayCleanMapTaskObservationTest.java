package br.com.redesurftank.havalshisuku.experimental;

import org.junit.Test;
import static org.junit.Assert.*;

public class CarPlayCleanMapTaskObservationTest {
    private static final String TASK = "  taskId=39: com.ts.carplay.app/.ui.display.view.CarPlayDisplayActivity bounds=[0,0][1920,720] userId=0 visible=true topActivity=ComponentInfo{com.ts.carplay.app/.ui.display.view.CarPlayDisplayActivity}";
    private static String header(int id, int display) { return "Stack id=" + id + " bounds=[0,0][1920,720] displayId=" + display + " userId=0\n"; }

    @Test public void actualVisibleMainTaskDoesNotDependOnTheOrchestratorCache() {
        CarPlayCleanMapTaskObservation value = CarPlayCleanMapTaskObservation.parse(header(1, 0) + TASK, 123);
        assertTrue(value.known);
        assertTrue(value.carPlayOnMain);
        assertFalse(value.carPlayOnSecondary);
        assertEquals(123, value.observedAtElapsedMs);
    }

    @Test public void hiddenMainAndAnySecondaryCarPlayTaskRemainIneligible() {
        CarPlayCleanMapTaskObservation hidden = CarPlayCleanMapTaskObservation.parse(header(1, 0) + TASK.replace("visible=true", "visible=false"), 123);
        assertTrue(hidden.known);
        assertFalse(hidden.carPlayOnMain);
        CarPlayCleanMapTaskObservation secondary = CarPlayCleanMapTaskObservation.parse(header(1, 0) + TASK + "\n" + header(2, 3) + TASK.replace("visible=true", "visible=false"), 123);
        assertTrue(secondary.known);
        assertTrue(secondary.carPlayOnSecondary);
        assertFalse(secondary.carPlayOnMain);
    }

    @Test public void nonProjectionMetadataDoesNotChangeTheStackDisplayOwner() {
        String sample = header(1, 0) + "  mConfiguration={ displayId=3 OEM-extra=value }\n" + TASK + "\nOEM metadata ignored\n";
        assertTrue(CarPlayCleanMapTaskObservation.parse(sample, 123).carPlayOnMain);
        CarPlayCleanMapTaskObservation empty = CarPlayCleanMapTaskObservation.parse(header(1, 0), 123);
        assertTrue(empty.known);
        assertFalse(empty.carPlayOnMain);
    }

    @Test public void malformedAmbiguousOrTruncatedProjectionEvidenceIsUnknown() {
        String good = header(1, 0) + TASK;
        String[] invalid = { "", TASK, "Error: permission denied\n" + good,
            good.replace("visible=true", ""), good.replace("visible=true", "visible=unknown"),
            good.replace("visible=true", "visible=false visible=true"),
            good.replace("displayId=0", "displayId=0 displayId=3"),
            good.replace("topActivity=ComponentInfo{com.ts.carplay.app/.ui.display.view.CarPlayDisplayActivity}", "topActivity=null"),
            good.replace("bounds=[0,0][1920,720] userId=", "bounds=garbage userId="),
            header(1, 0) + "Stack id =2 displayId=3\n" + TASK,
            good + "\n" + header(1, 3), good + "\n" + TASK,
            good + "\n" + header(2, 3) + "  taskId=99: com.ts.carplay",
            good + "\n" + header(2, 3) + "  taskId=99: com.other.app/.MainActivity bounds=[0,0][1920,720] userId=0 visible=false",
            good + "\n" + header(2, 3) + "  taskId=99:",
            good + "\n" + new String(new char[262144]).replace('\0', 'x') };
        for (String sample : invalid) assertFalse(sample, CarPlayCleanMapTaskObservation.parse(sample, 123).known);
        assertFalse(CarPlayCleanMapTaskObservation.parse(good, -1).known);
        assertFalse(CarPlayCleanMapTaskObservation.parse(null, 123).known);
    }

    @Test public void completeUnrelatedTaskIsAcceptedButItsTruncatedSuffixIsUnknown() {
        String other = TASK.replace("com.ts.carplay.app", "com.other.app").replace("taskId=39", "taskId=99");
        String sample = header(1, 0) + TASK + "\n" + header(2, 1) + other;
        assertTrue(CarPlayCleanMapTaskObservation.parse(sample, 123).carPlayOnMain);
        assertFalse(CarPlayCleanMapTaskObservation.parse(sample.substring(0, sample.length() - 10), 123).known);
    }
}
