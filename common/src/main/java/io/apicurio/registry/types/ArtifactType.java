
package io.apicurio.registry.types;

public class ArtifactType {

    private ArtifactType() {
    }

    // TODO: Turn into enum, which can contain both a string value and a numeric identifier.
    // See io.apicurio.registry.storage.impl.kafkasql.serde.ArtifactTypeOrdUtil
    public static final String AVRO = "AVRO";
    public static final String PROTOBUF = "PROTOBUF";
    public static final String JSON = "JSON";
    public static final String OPENAPI = "OPENAPI";
    public static final String ASYNCAPI = "ASYNCAPI";
    public static final String GRAPHQL = "GRAPHQL";
    public static final String KCONNECT = "KCONNECT";
    public static final String WSDL = "WSDL";
    public static final String XSD = "XSD";
    public static final String XML = "XML";

}
