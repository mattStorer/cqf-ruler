package org.opencds.cqf.ruler.plugin.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.hl7.fhir.dstu3.model.Library;
import org.junit.jupiter.api.Test;

public class VersionUtilitiesTest implements VersionUtilities {

    @Test
    public void testVersionCompare() {
        int result = this.compareVersions("1.0.0", "0.9.9");
        assertEquals(1, result);

        result = this.compareVersions("0.9.9", "1.0.0");
        assertEquals(-1, result);

        result = this.compareVersions("1.0.0", "1.0.0");
        assertEquals(0, result);

        result = this.compareVersions(null, null);
        assertEquals(0, result);

        result = this.compareVersions(null, "1.0.0");
        assertEquals(1, result);

        result = this.compareVersions("1.0.0", null);
        assertEquals(-1, result);

        result = this.compareVersions("1.0", "0.0.9");
        assertEquals(1, result);

        result = this.compareVersions("0", "1.1.2");
        assertEquals(-1, result);
    }

    @Test
    public void testSelectFromList() {
        List<Library> libraries = Arrays.asList(new Library().setVersion("1.0.0"), new Library().setVersion("0.0.1"), new Library());

        Function<Library, String> getVersion = l -> l.getVersion();

        // Gets matching version
        Library lib = this.selectFromList(libraries, "1.0.0", getVersion);
        assertEquals("1.0.0", lib.getVersion());

        // Gets max version (null is considering max version)
        lib = this.selectFromList(libraries, "2.0.0", getVersion);
        assertNull(lib.getVersion());
    } 
}
