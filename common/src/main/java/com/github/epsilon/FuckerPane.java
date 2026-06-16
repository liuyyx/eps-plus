package com.github.epsilon;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;

public class FuckerPane {

    public static void show() {
        if (!isWinOrMac()) {
            return;
        }

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showBlockingDialog();
            } else {
                SwingUtilities.invokeAndWait(FuckerPane::showBlockingDialog);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ignored) {
        }
    }

    private static void showBlockingDialog() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JOptionPane optionPane = new JOptionPane(
                """
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
                        
                        """,
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

    private static boolean isWinOrMac() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win") || osName.contains("mac");
    }

}
