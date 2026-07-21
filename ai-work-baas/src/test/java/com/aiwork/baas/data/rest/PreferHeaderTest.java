package com.aiwork.baas.data.rest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PreferHeaderTest {

    @Test
    void nullAndBlankHeadersUseDefaults() {
        assertThat(PreferHeader.parse(null)).isEqualTo(new PreferHeader(false, false));
        assertThat(PreferHeader.parse("  ")).isEqualTo(new PreferHeader(false, false));
    }

    @Test
    void knownTokensAreParsedAndUnknownTokensAreIgnored() {
        PreferHeader prefer = PreferHeader.parse("unknown=value, count=exact, return=representation");

        assertThat(prefer).isEqualTo(new PreferHeader(true, true));
    }

}
