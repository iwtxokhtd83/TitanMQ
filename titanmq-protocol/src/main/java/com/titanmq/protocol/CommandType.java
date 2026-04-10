package com.titanmq.protocol;

/**
 * Wire protocol command types.
 */
public enum CommandType {

    // Producer commands
    PRODUCE((byte) 0x01),
    PRODUCE_ACK((byte) 0x02),

    // Consumer commands
    FETCH((byte) 0x10),
    FETCH_RESPONSE((byte) 0x11),
    COMMIT_OFFSET((byte) 0x12),
    COMMIT_OFFSET_ACK((byte) 0x13),

    // Admin commands
    CREATE_TOPIC((byte) 0x20),
    DELETE_TOPIC((byte) 0x21),
    DESCRIBE_TOPIC((byte) 0x22),
    ADMIN_RESPONSE((byte) 0x23),
    DECLARE_EXCHANGE((byte) 0x24),
    BIND_EXCHANGE((byte) 0x25),
    UNBIND_EXCHANGE((byte) 0x26),

    // Cluster commands
    HEARTBEAT((byte) 0x30),
    VOTE_REQUEST((byte) 0x31),
    VOTE_RESPONSE((byte) 0x32),
    APPEND_ENTRIES((byte) 0x33),
    APPEND_ENTRIES_RESPONSE((byte) 0x34),

    // Error
    ERROR((byte) 0xFF);

    private final byte code;

    CommandType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static CommandType fromCode(byte code) {
        for (CommandType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("Unknown command code: " + code);
    }
}
