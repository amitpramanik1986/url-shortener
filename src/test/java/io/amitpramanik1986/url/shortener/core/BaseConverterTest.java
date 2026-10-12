package io.amitpramanik1986.url.shortener.core;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

@ExtendWith(MockitoExtension.class)
@Slf4j
class BaseConverterTest {

    @Test
    void testBaseConverter(){
        //List.of(1_000_000L)
        List.of(0L, 30L, 63L, 64L, 100L, 1_000L, 1_000_000L,1_000_000_000_000_000L)
                .forEach(decimalInput->this.testBaseConverter(decimalInput));

    }

    private void testBaseConverter(long decimalInput){
        String key = BaseConverter.toBase(decimalInput);
        Assertions.assertNotNull(key);
        log.info("Got {} for {}", key, decimalInput);
        Long decimalOutput = BaseConverter.toDecimal(key);
        Assertions.assertEquals(decimalInput, decimalOutput, "Expected "+decimalInput);

    }



}
