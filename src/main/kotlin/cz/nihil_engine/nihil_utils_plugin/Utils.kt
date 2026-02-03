package cz.nihil_engine.nihil_utils_plugin

import kotlin.random.Random

fun generateRandomAssertID() = "0x%08X".format(Random.nextInt())

fun generateRandomRTTIID()  = "nihil::rtti::ID{0x%017X, 0x%017X}".format(Random.nextLong(), Random.nextLong())