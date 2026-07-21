package com.aiwork.baas.data.exec;

import com.aiwork.baas.data.error.DataApiException;
import com.fasterxml.jackson.core.JsonFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedJsonBufferTest {

    @Test
    void oversizedSingleValueNeverGrowsBackingBufferPastLimit() throws Exception {
        BoundedJsonBuffer buffer = new BoundedJsonBuffer(new JsonFactory(), 32);
        buffer.generator().writeStartArray();
        buffer.generator().writeString("x".repeat(256));

        assertThatThrownBy(buffer::checkLimit).isInstanceOf(DataApiException.class)
            .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(413));
        assertThat(buffer.bufferedBytes()).isLessThanOrEqualTo(32);
    }

}
