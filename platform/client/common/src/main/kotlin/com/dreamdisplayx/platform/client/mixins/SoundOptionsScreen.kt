package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.screens.options.SoundOptionsScreen
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Proxy
import java.util.function.DoubleFunction
import java.util.function.ToDoubleFunction

/** Adds the global Dream DisplaysX volume multiplier to Minecraft's vanilla sound options. */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(SoundOptionsScreen::class)
open class SoundOptionsScreenMixin {
    @Inject(
        method = ["getAllSoundOptionsExceptMaster"],
        at = [At("RETURN")],
        cancellable = true,
        require = 0,
    )
    private fun appendDreamDisplaysXVolume(cir: CallbackInfoReturnable<Array<OptionInstance<*>>>) {
        val old = cir.returnValue ?: return
        val option = createOption() ?: return
        val result = ReflectArray.newInstance(old.javaClass.componentType, old.size + 1)
        java.lang.System.arraycopy(old, 0, result, 0, old.size)
        ReflectArray.set(result, old.size, option)
        @Suppress("UNCHECKED_CAST")
        cir.returnValue = result as Array<OptionInstance<*>>
    }

    private fun createOption(): OptionInstance<*>? = runCatching {
        val optionClass = OptionInstance::class.java
        val unitDouble = Class.forName("net.minecraft.client.OptionInstance\u0024UnitDouble")
            .getField("INSTANCE").get(null)
        val slider = unitDouble.javaClass.getMethod(
            "xmap", DoubleFunction::class.java, ToDoubleFunction::class.java,
        ).invoke(
            unitDouble,
            DoubleFunction<Double> { it * 2.0 },
            ToDoubleFunction<Double> { it / 2.0 },
        )
        val captionType = Class.forName("net.minecraft.client.OptionInstance\u0024CaptionBasedToString")
        val caption = Proxy.newProxyInstance(captionType.classLoader, arrayOf(captionType)) { _, method, args ->
            // CaptionBasedToString extends Function<T, Component>, so its single argument is args[0].
            if (method.name != "apply") return@newProxyInstance null
            val value = (args?.getOrNull(0) as? Number)?.toDouble() ?: return@newProxyInstance null
            Component.translatable("options.dreamdisplayx.global_volume", "%.2fx".format(value))
        }
        val tooltip = optionClass.getMethod("noTooltip").invoke(null)
        val listenerType = optionClass.constructors.first { it.parameterTypes.size == 6 }.parameterTypes[5]
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, _, args ->
            val value = (args?.getOrNull(0) as? Number)?.toDouble() ?: return@newProxyInstance null
            val config = ClientStateManager.config
            config.globalAudioMultiplier = value.coerceIn(0.0, 2.0)
            config.save()
            // Re-apply the multiplier to every live display right away; config.save() alone leaves
            // already-playing volume untouched.
            DisplayRegistry.getScreens().forEach { it.applyEffectiveVolume() }
            null
        }
        val ctor = optionClass.constructors.first { it.parameterTypes.size == 6 }
        ctor.newInstance(
            "options.dreamdisplayx.global_volume",
            tooltip,
            caption,
            slider,
            ClientStateManager.config.globalAudioMultiplier.coerceIn(0.0, 2.0),
            listener,
        ) as OptionInstance<*>
    }.getOrNull()
}
