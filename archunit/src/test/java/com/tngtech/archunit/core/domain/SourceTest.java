package com.tngtech.archunit.core.domain;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.Source.Md5sum;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.tngtech.archunit.testutil.Assertions.assertThat;
import static com.tngtech.archunit.testutil.TestUtils.uriOf;
import static com.tngtech.archunit.testutil.TestUtils.urlOf;
import static com.tngtech.java.junit.dataprovider.DataProviders.$;
import static com.tngtech.java.junit.dataprovider.DataProviders.$$;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

@RunWith(DataProviderRunner.class)
public class SourceTest {
    @After
    public void tearDown() {
        ArchConfiguration.get().reset();
    }

    @Test
    public void source_file_name() {
        Source source = new Source(uriOf(Object.class), Optional.of("SomeClass.java"), false);
        assertThat(source.getFileName()).as("source file name").contains("SomeClass.java");

        source = new Source(uriOf(Object.class), Optional.<String>empty(), false);
        assertThat(source.getFileName()).as("source file name").isEmpty();
    }

    @DataProvider
    public static List<List<?>> expectedHexCodes() {
        List<List<?>> testCases = new ArrayList<>();
        testCases.add(ImmutableList.of(new byte[]{0}, "00"));
        testCases.add(ImmutableList.of(new byte[]{15}, "0f"));
        testCases.add(ImmutableList.of(new byte[]{16}, "10"));
        testCases.add(ImmutableList.of(new byte[]{31}, "1f"));
        testCases.add(ImmutableList.of(new byte[]{32}, "20"));
        testCases.add(ImmutableList.of(new byte[]{(byte) 255}, "ff"));
        testCases.add(ImmutableList.of(new byte[]{(byte) 128, 37, 45, 22, 99}, "80252d1663"));
        return testCases;
    }

    @Test
    @UseDataProvider("expectedHexCodes")
    public void toHex_works(byte[] input, String expectedHexString) {
        assertThat(Md5sum.toHex(input)).as("Bytes").isEqualTo(expectedHexString);
    }

    @DataProvider
    public static Object[][] classes() {
        return $$($(fileUrl()), $(jarUrl()));
    }

    @Test
    @UseDataProvider("classes")
    public void calculates_md5_correctly(URL url) throws Exception {
        Source source = newSource(url);

        assertThat(source.getUri()).as("source URI").isEqualTo(url.toURI());
        assertThat(source.getMd5sum().asBytes()).isEqualTo(expectedMd5BytesAt(url));
    }

    @Test
    @UseDataProvider("classes")
    public void equals_hashcode_and_toString(URL url) throws Exception {
        Source source = newSource(url);
        Source equalSource = newSource(url);

        assertThat(source).as("source").isEqualTo(equalSource);
        assertThat(source.hashCode()).as("hashcode").isEqualTo(equalSource.hashCode());
        assertThat(source).as("source").isNotEqualTo(newSource(urlOf(Object.class)));
        String expectedToString = String.format("%s [md5='%s']", url, Md5sum.toHex(expectedMd5BytesAt(url)));
        assertThat(source.toString()).as("source.toString()").isEqualTo(expectedToString);
    }

    @DataProvider
    public static Object[][] equalMd5Sums() {
        return $$(
                $(Md5sum.UNDETERMINED, Md5sum.UNDETERMINED),
                $(Md5sum.NOT_SUPPORTED, Md5sum.NOT_SUPPORTED),
                $(md5sumOf("anything"), md5sumOf("anything")));
    }

    @Test
    @UseDataProvider("equalMd5Sums")
    public void positive_equals_hashcode_of_md5sums(Md5sum first, Md5sum second) {
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @DataProvider
    public static List<List<?>> unequalMd5Sums() {
        return createUnequalTestCasesFor(
                Md5sum.UNDETERMINED,
                Md5sum.NOT_SUPPORTED,
                md5sumOf("anything"),
                md5sumOf("totallyDifferent"));
    }

    private static List<List<?>> createUnequalTestCasesFor(Md5sum... md5sums) {
        List<List<?>> result = new ArrayList<>();
        List<Md5sum> input = ImmutableList.copyOf(md5sums);
        ArrayList<Md5sum> shifting = Lists.newArrayList(md5sums);
        for (int i = 1; i < md5sums.length; i++) {
            Collections.rotate(shifting, 1);
            result.addAll(zip(input, shifting));
        }
        return result;
    }

    private static List<List<?>> zip(List<?> first, List<?> second) {
        return IntStream.range(0, first.size())
                .mapToObj(i -> ImmutableList.of(first.get(i), second.get(i)))
                .collect(toList());
    }

    @Test
    @UseDataProvider("unequalMd5Sums")
    public void negative_equals_of_md5sums(Md5sum first, Md5sum second) {
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    public void compensates_error_on_md5_calculation() throws Exception {
        Source source = newSource(new URI("bummer"));

        assertThat(source.getMd5sum()).isEqualTo(Md5sum.UNDETERMINED);
    }

    @Test
    public void disables_md5_calculation_via_parameter() throws Exception {
        Source source = new Source(uriOf(getClass()), Optional.of("any.java"), false);
        assertThat(source.getMd5sum()).isEqualTo(Md5sum.DISABLED);

        source = new Source(uriOf(getClass()), Optional.of("any.java"), true);
        assertThat(source.getMd5sum().asBytes()).isEqualTo(expectedMd5BytesAt(source.getUri().toURL()));
    }

    private Source newSource(URL url) throws URISyntaxException {
        return newSource(url.toURI());
    }

    private Source newSource(URI uri) {
        return new Source(uri, Optional.<String>empty(), true);
    }

    private static Md5sum md5sumOf(String data) {
        return TestUtils.md5sumOf(data.getBytes(UTF_8));
    }

    private static byte[] expectedMd5BytesAt(URL url) throws IOException, NoSuchAlgorithmException {
        byte[] bytes = bytesAt(url);
        return MessageDigest.getInstance("MD5").digest(bytes);
    }

    public static byte[] bytesAt(URL url) throws IOException {
        return ByteStreams.toByteArray(url.openStream());
    }

    private static Object fileUrl() {
        return urlOf(SourceTest.class);
    }

    private static Object jarUrl() {
        return urlOf(Rule.class);
    }
}
