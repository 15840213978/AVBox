plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.undcover.freedom.pyramid"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // 脱糖:minSdk 24 下 java.time / java.util.stream / java.nio.file 等 JDK 库 API 改写为 j$ 实现
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // 脱糖运行时库(实际打包在 :app,此处声明以启用本模块代码的脱糖)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

chaquopy {
    defaultConfig {
        // Chaquopy 17 可用版本为 3.10~3.14(原 3.8 已下线);
        // 注:3.10 兼容 armeabi-v7a,但本项目各模块 abiFilters 仅 arm64-v8a,不构建 32 位产物,
        // armeabi-v7a 老设备不可安装(设计取舍)
        version = "3.10"
        // 原配置写死了开发者本机的 Python 3.8;路径存在时沿用,否则由 Chaquopy 从 PATH 自动探测
        val localBuildPython = "D:/Programs/Python/Python38/python.exe"
        if (file(localBuildPython).exists()) {
            buildPython(localBuildPython)
        }
        pip {
            // 2026-09-08:pypi.org/chaquo.com 经系统代理极不稳定(SSL 握手超时),改用阿里镜像;
            // Android 平台 wheel(PyPI 无此 tag)由本地预置目录兜底——从 pip http-v2 缓存提取,
            // 提取脚本见 wheels/chaquopy-prebuilt/extract_wheels.py
            options("-i", "https://mirrors.aliyun.com/pypi/simple/")
            options("--find-links", file("wheels/chaquopy-prebuilt").absolutePath)
            install("lxml")
            install("ujson")
            install("pyquery==2.0.2")
            install("requests")
            // jsonpath 0.54 只有 py2 源码包、无 wheel,本地移植后以 wheel 安装(见 wheels/README.md)
            install(file("wheels/jsonpath-0.54-py3-none-any.whl").absolutePath)
            install("cachetools")
            install("pycryptodome")
            install("beautifulsoup4")
        }
    }
    sourceSets {
        getByName("main") {
            setSrcDirs(listOf("src/python"))
        }
    }
}
