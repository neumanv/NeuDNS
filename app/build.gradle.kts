plugins {
    id("com.android.application")
}

// Firma de la versión publicada. Se lee de variables de entorno (GitHub Actions) para no
// guardar nunca la clave en el repositorio. Sin ellas se firma con la clave de depuración.
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_FILE")

android {
    namespace = "io.github.neumanv.neudns"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.neumanv.neudns"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                fun required(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }
                    ?: throw GradleException("Falta la variable de entorno $name para firmar la APK")
                storeFile = file(releaseKeystore)
                storePassword = required("SIGNING_STORE_PASSWORD")
                keyAlias = required("SIGNING_KEY_ALIAS")
                keyPassword = required("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 elimina el código que no se usa y reduce la APK a una fracción.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // No incrustar en la APK información del repositorio local (ruta y commit).
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No incluir en la APK el bloque de metadatos de dependencias (solo lo usa Google Play).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        // Los tests de la lógica de red usan SystemClock; fuera de Android devuelve 0.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/*.kotlin_builtins", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
