package me.custom.biliextras.hook

abstract class BaseHook(val mClassLoader: ClassLoader) {
    abstract fun startHook()
}
