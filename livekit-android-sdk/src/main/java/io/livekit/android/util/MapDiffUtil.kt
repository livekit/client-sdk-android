package io.livekit.android.util

fun <K, V> diffMapChange(newMap: Map<K, V>, oldMap: Map<K, V>, defaultValue: V): MutableMap<K, V> {
    val allKeys = newMap.keys + oldMap.keys
    val diff = mutableMapOf<K, V>()

    for (key in allKeys) {
        if (newMap[key] != oldMap[key]) {
            diff[key] = newMap[key] ?: defaultValue
        }
    }

    return diff
}
