/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.common.thrift.logging;

import org.apache.thrift.protocol.TType;

final class TJsonProtocolUtils {

    // Forked from:
    // https://github.com/apache/thrift/blob/7aea524e13d2a6fbeb942716f4224cd51bfda0ea/lib/java/src/main/java/org/apache/thrift/protocol/TJSONProtocol.java#L85-L182

    private static final String NAME_BOOL = "tf";
    private static final String NAME_BYTE = "i8";
    private static final String NAME_I16 = "i16";
    private static final String NAME_I32 = "i32";
    private static final String NAME_I64 = "i64";
    private static final String NAME_DOUBLE = "dbl";
    private static final String NAME_STRUCT = "rec";
    private static final String NAME_STRING = "str";
    private static final String NAME_MAP = "map";
    private static final String NAME_LIST = "lst";
    private static final String NAME_SET = "set";

    static String getTypeNameForTypeID(byte typeID) {
        switch (typeID) {
            case TType.BOOL:
                return NAME_BOOL;
            case TType.BYTE:
                return NAME_BYTE;
            case TType.I16:
                return NAME_I16;
            case TType.I32:
            case TType.ENUM:
                return NAME_I32;
            case TType.I64:
                return NAME_I64;
            case TType.DOUBLE:
                return NAME_DOUBLE;
            case TType.STRING:
                return NAME_STRING;
            case TType.STRUCT:
                return NAME_STRUCT;
            case TType.MAP:
                return NAME_MAP;
            case TType.SET:
                return NAME_SET;
            case TType.LIST:
                return NAME_LIST;
            default:
                throw new IllegalArgumentException("Unrecognized type: " + typeID);
        }
    }

    static byte getTypeIdForTypeName(String name) {
        byte result = TType.STOP;
        if (name.length() > 1) {
            switch (name.charAt(0)) {
                case 'd':
                    result = TType.DOUBLE;
                    break;
                case 'i':
                    switch (name.charAt(1)) {
                        case '8':
                            result = TType.BYTE;
                            break;
                        case '1':
                            result = TType.I16;
                            break;
                        case '3':
                            result = TType.I32;
                            break;
                        case '6':
                            result = TType.I64;
                            break;
                    }
                    break;
                case 'l':
                    result = TType.LIST;
                    break;
                case 'm':
                    result = TType.MAP;
                    break;
                case 'r':
                    result = TType.STRUCT;
                    break;
                case 's':
                    if (name.charAt(1) == 't') {
                        result = TType.STRING;
                    } else if (name.charAt(1) == 'e') {
                        result = TType.SET;
                    }
                    break;
                case 't':
                    result = TType.BOOL;
                    break;
            }
        }
        if (result == TType.STOP) {
            throw new IllegalArgumentException("Unrecognized type: " + name);
        }
        return result;
    }

    private TJsonProtocolUtils() {}
}
