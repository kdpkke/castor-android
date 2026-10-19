plugins { alias(libs.plugins.buf) }

buf {
  toolVersion = libs.versions.buf.get()
  generate { templateFileLocation = file("buf.gen.yaml") }
}

tasks.named("bufGenerate") {
  inputs.files(
    fileTree("../../proto") { include("**/*.proto") },
    file("../../buf.yaml"),
    file("../../buf.lock"),
  )
}
