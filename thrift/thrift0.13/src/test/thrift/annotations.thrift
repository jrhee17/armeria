namespace java testing.thrift.main

include "main.thrift"

struct SecretStruct {
    1: string hello;
    2: string secret (grade = "red");
}

service SecretService {
    // annotations on method parameters are not supported for now
    SecretStruct hello(1: SecretStruct req)
}

struct OptionalFooStruct {
    1: optional bool boolVal,
    2: optional i8 byteVal,
    3: optional i16 i16Val,
    4: optional i32 i32Val,
    5: optional i64 i64Val,
    6: optional double doubleVal,
    7: optional string stringVal,
    8: optional binary binaryVal,
    /* 9: slist slistVal, */
    10: optional  main.FooEnum enumVal,
    11: optional  main.FooUnion unionVal,
    12: optional  map<string, main.FooEnum> mapVal,
    13: optional  set<main.FooUnion> setVal,
    14: optional  list<string> listVal,
    15: optional OptionalFooStruct selfRef
}

struct RequiredFooStruct {
    1: required bool boolVal,
    2: required i8 byteVal,
    3: required i16 i16Val,
    4: required i32 i32Val,
    5: required i64 i64Val,
    6: required double doubleVal,
    7: required string stringVal,
    8: required binary binaryVal,
    /* 9: slist slistVal, */
    10: required main.FooEnum enumVal,
    11: required main.FooUnion unionVal,
    12: required map<string, main.FooEnum> mapVal,
    13: required set<main.FooUnion> setVal,
    14: required list<string> listVal,
    15: optional OptionalFooStruct selfRef
}

struct TypedefedFooStruct {
    1: main.TypedefedBool boolVal,
    2: main.TypedefedByte byteVal,
    3: main.TypedefedI16 i16Val,
    4: main.TypedefedI32 i32Val,
    5: main.TypedefedI64 i64Val,
    6: main.TypedefedDouble doubleVal,
    7: main.TypedefedString stringVal,
    8: main.TypedefedBinary binaryVal,
    /* 9: slist slistVal, */
    10: main.TypedefedEnum enumVal,
    11: main.TypedefedUnion unionVal,
    12: main.TypedefedEnumMap mapVal,
    13: main.TypedefedSetUnion setVal,
    14: main.TypedefedListString listVal,
}
