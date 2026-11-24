# Full Java protobuf messages use FieldAccessorTable to resolve generated methods by name.
-keepclassmembers,allowoptimization class * extends com.google.protobuf.GeneratedMessage {
    public <methods>;
}
-keepclassmembers,allowoptimization class * extends com.google.protobuf.GeneratedMessage$Builder {
    public <methods>;
}
-keepclassmembers,allowoptimization enum * implements com.google.protobuf.ProtocolMessageEnum {
    public static *** valueOf(com.google.protobuf.Descriptors$EnumValueDescriptor);
    public com.google.protobuf.Descriptors$EnumValueDescriptor getValueDescriptor();
}
