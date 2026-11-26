# A-Music 的 R8 规则。
# release 里 **isMinifyEnabled = true / isShrinkResources = true**（见 app/build.gradle.kts）。
#
# 这里没有自定义规则是有意的：Room、Media3、Compose 这些库都自带 consumer rules，
# 清单里声明的组件（Activity / Service）AGP 也会自动 keep —— release 包已装机实测正常运行。
#
# 只有新引入"靠反射拿名字"的东西（Gson 数据类、XML 里引用的自定义 View 等）时才需要补 keep 规则，
# 而且加完要在 release 包上验证一次，别凭感觉加。
