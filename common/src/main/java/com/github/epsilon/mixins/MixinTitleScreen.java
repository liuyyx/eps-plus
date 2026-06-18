package com.github.epsilon.mixins;

import com.github.epsilon.gui.screen.MainMenuScreen;
import com.github.epsilon.modules.impl.ClientSetting;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.swing.*;
import java.awt.*;
import java.util.Base64;

import static com.github.epsilon.Constants.mc;

@Mixin(TitleScreen.class)
public class MixinTitleScreen {

    @Unique
    private static boolean epsilon$fuckerPaneShown;

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void redirectToMainMenu(CallbackInfo ci) {
        if (!epsilon$fuckerPaneShown) {
            epsilon$fuckerPaneShown = true;

            String osName = System.getProperty("os.name", "").toLowerCase();
            if (!osName.contains("win") && !osName.contains("mac")) {
                return; // 我去！、。居然是老，。安卓。必须。，安排，。
            }

            String b = """
                                    Epsilon 客户端完全免费，请勿向任何人付费购买。
                    
                                                3787275604
                                                3787275604
                                                3787275604
                    
                    你妈了个逼的倒卖epsilon倒出幻觉了是吧？就你这底层蛆虫也配在群里跳脸？笑死我了，
                    赚那仨瓜俩枣的冥币是给你野爹凑棺材本呢，还是给你那站街的老娘买双破丝袜？真tm招笑。
                    跟风狗一条还学人当倒爷，你那核桃仁脑子也就配在同戈鱼上当气丐.我看你以后找的老婆都是三手破鞋，
                    结婚当天就仙人跳你，生个儿子没屁眼，户口本翻烂就剩你这一个活畜生，
                    逼逼赖赖的废物还敢出来现眼？缩头乌龟当上瘾了是吧，被你亲妈喊去写作业了？
                    辍学蛆虫大字不识几个，倒卖epsilon就是你人生巅峰了？就这？就这？
                    哈哈哈哈哈哈，你全家暴毙那天我都得雇个唢呐班子去你坟头吹一整宿，庆祝世上少了个倒卖狗。
                    
                    """;

            // 我去居然是 base64
            String ez = "CiAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIEVwc2lsb24g5a6i5oi356uv5a6M5YWo5YWN6LS577yM6K+35Yu/5ZCR5Lu75L2V5Lq65LuY6LS56LSt5Lmw44CCCiAgICAgICAgICAgICAgICAgICAgCiAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIDM3ODcyNzU2MDQKICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgMzc4NzI3NTYwNAogICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAzNzg3Mjc1NjA0CiAgICAgICAgICAgICAgICAgICAgCiAgICAgICAgICAgICAgICAgICAg5L2g5aaI5LqG5Liq6YC855qE5YCS5Y2WZXBzaWxvbuWAkuWHuuW5u+inieS6huaYr+WQp++8n+WwseS9oOi/meW6leWxguibhuiZq+S5n+mFjeWcqOe+pOmHjOi3s+iEuO+8n+eskeatu+aIkeS6hu+8jAogICAgICAgICAgICAgICAgICAgIOi1mumCo+S7qOeTnOS/qeaeo+eahOWGpeW4geaYr+e7meS9oOmHjueIueWHkeajuuadkOacrOWRou+8jOi/mOaYr+e7meS9oOmCo+ermeihl+eahOiAgeWomOS5sOWPjOegtOS4neiinO+8n+ecn3Rt5oub56yR44CCCiAgICAgICAgICAgICAgICAgICAg6Lef6aOO54uX5LiA5p2h6L+Y5a2m5Lq65b2T5YCS54i377yM5L2g6YKj5qC45qGD5LuB6ISR5a2Q5Lmf5bCx6YWN5Zyo5ZCM5oiI6bG85LiK5b2T5rCU5LiQLuaIkeeci+S9oOS7peWQjuaJvueahOiAgeWphumDveaYr+S4ieaJi+egtOmei++8jAogICAgICAgICAgICAgICAgICAgIOe7k+WpmuW9k+WkqeWwseS7meS6uui3s+S9oO+8jOeUn+S4quWEv+WtkOayoeWxgeecvO+8jOaIt+WPo+acrOe/u+eDguWwseWJqeS9oOi/meS4gOS4qua0u+eVnOeUn++8jAogICAgICAgICAgICAgICAgICAgIOmAvOmAvOi1lui1lueahOW6n+eJqei/mOaVouWHuuadpeeOsOecvO+8n+e8qeWktOS5jOm+n+W9k+S4iueYvuS6huaYr+WQp++8jOiiq+S9oOS6suWmiOWWiuWOu+WGmeS9nOS4muS6hu+8nwogICAgICAgICAgICAgICAgICAgIOi+jeWtpuibhuiZq+Wkp+Wtl+S4jeivhuWHoOS4qu+8jOWAkuWNlmVwc2lsb27lsLHmmK/kvaDkurrnlJ/lt4Xls7DkuobvvJ/lsLHov5nvvJ/lsLHov5nvvJ8KICAgICAgICAgICAgICAgICAgICDlk4jlk4jlk4jlk4jlk4jlk4jvvIzkvaDlhajlrrbmmrTmr5npgqPlpKnmiJHpg73lvpfpm4fkuKrllKLlkZDnj63lrZDljrvkvaDlnZ/lpLTlkLnkuIDmlbTlrr/vvIzluobnpZ3kuJbkuIrlsJHkuobkuKrlgJLljZbni5fjgIIKICAgICAgICAgICAgICAgICAgICAKICAgICAgICAgICAgICAgICAgICA=";

            JOptionPane optionPane = new JOptionPane(
                    new String(Base64.getDecoder().decode(ez)),
                    JOptionPane.WARNING_MESSAGE,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    new Object[]{"确定"},
                    "确定"
            );

            JDialog dialog = optionPane.createDialog(null, "Epsilon 免费声明");
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.setAlwaysOnTop(true);
            dialog.setModal(true);
            dialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setVisible(true);
            dialog.dispose();
        }

        if (ClientSetting.INSTANCE.useMainMenu.getValue()) {
            ci.cancel();
            mc.setScreen(MainMenuScreen.INSTANCE);
        }
    }

}
