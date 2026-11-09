package com.netpulse.common.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {
    @Test void quotesPerRfc4180() {
        assertThat(Csv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Csv.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
    }

    @Test void neutralizesSpreadsheetFormulas() {
        for (String attack : new String[]{"=HYPERLINK(\"http://x\")", "+1", "-1", "@SUM(A1)"}) {
            assertThat(Csv.cell(attack).replace("\"", "")).startsWith("'");
        }
    }

    @Test void numbersAndNullsAreLeftAlone() {
        assertThat(Csv.cell(-5)).isEqualTo("-5");
        assertThat(Csv.cell(null)).isEmpty();
    }

    @Test void rowEndsWithCrLf() {
        assertThat(Csv.row("a", 1)).isEqualTo("a,1\r\n");
    }
}
