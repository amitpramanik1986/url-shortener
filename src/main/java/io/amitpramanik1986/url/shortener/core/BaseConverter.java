package io.amitpramanik1986.url.shortener.core;

import jakarta.validation.constraints.NotNull;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class BaseConverter {

    private static final char[] BASE;
    private static final ConcurrentHashMap<Character,Integer> SYMBOL_POSITION;

    static{
        //RFC 3986 unreserved characters
        BASE = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_~".toCharArray();

        int mapSize =  1 << (31 - Integer.numberOfLeadingZeros(BASE.length ));
        SYMBOL_POSITION = new ConcurrentHashMap<>(mapSize);
        int index=0;
        for(Character c: BASE){
            SYMBOL_POSITION.put(c,index++);
        }
    }

    public static String toBase(long decimal){
        StringBuilder sb = new StringBuilder(10);
        int reminder;
        do{
            reminder = (int)(decimal % BASE.length);
            sb.append(BASE[reminder]);
            decimal = decimal/BASE.length;
        }while (decimal > 0);
        return sb.reverse().toString();
    }

    public static long toDecimal(@NotNull String base) {
        Objects.requireNonNull(base,"Argument base can't be null");
        if(base.isBlank())
            throw new IllegalArgumentException("Argument base can't be blank");

        if(base.length() == 1)
            return SYMBOL_POSITION.get(base.charAt(0));

        long result = 0L;
        long devisor = 0L;

        for(int index = 0; index < base.length()-1; index++){
            if(index == 0){
                devisor = SYMBOL_POSITION.get(base.charAt(0));
            }

            result = devisor * BASE.length + SYMBOL_POSITION.get(base.charAt(index+1));
            devisor = result;
        }

        return result;
    }
}
