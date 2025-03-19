namespace java testing.thrift.main

struct HelloRequest {
    1: string publicField
    2: string omitField (logging = "omit"),
    3: string maskField (logging = "mask"),
}

struct HelloResponse {
    1: string publicField
    2: string omitField (logging = "omit")
    3: string maskField (logging = "mask")
}

service ThriftMetadataService {
    HelloResponse hello(1: HelloRequest req)
}
