package me.custom.biliextras.playback;

import android.content.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import me.custom.biliextras.sponsorblock.HostFunction0Proxy;

/**
 * Host {@code Function0} proxy for in-player playback / auto-next menu actions.
 */
public final class PlaybackMenuActionHandler implements InvocationHandler {
    public static final int OPEN_FOREGROUND_AUTO_NEXT = 100;
    public static final int TOGGLE_FOREGROUND_AUTO_NEXT = 101;
    public static final int OPEN_FOREGROUND_SCOPES = 102;
    public static final int OPEN_FOREGROUND_PREFS = 103;
    public static final int OPEN_FOREGROUND_ORIENTATION = 104;
    public static final int OPEN_FOREGROUND_UP = 105;
    public static final int OPEN_FOREGROUND_TAG = 106;
    public static final int TOGGLE_STORY_BACKGROUND = 107;
    public static final int TOGGLE_MEDIA_BUTTON = 108;
    public static final int TOGGLE_DISABLE_CHAPTER = 109;
    public static final int BLOCK_STORY_CURRENT_UP = 110;

    private final int actionId;
    private final Context context;
    private final Object toggleFlow;

    private PlaybackMenuActionHandler(int actionId, Context context, Object toggleFlow) {
        this.actionId = actionId;
        this.context = context;
        this.toggleFlow = toggleFlow;
    }

    public static Object create(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context
    ) {
        return bind(hostClassLoader, fn0Interface, actionId, context, null);
    }

    public static Object createToggle(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Object checkedFlow
    ) {
        return bind(hostClassLoader, fn0Interface, actionId, null, checkedFlow);
    }

    private static Object bind(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context,
            Object toggleFlow
    ) {
        HostFunction0Proxy.installHostUnit(fn0Interface);
        PlaybackMenuActionHandler handler = new PlaybackMenuActionHandler(actionId, context, toggleFlow);
        return Proxy.newProxyInstance(hostClassLoader, new Class<?>[]{fn0Interface}, handler);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("invoke".equals(name)) {
            dispatch();
            return HostFunction0Proxy.hostUnitInstance();
        }
        if ("equals".equals(name)) {
            return proxy == args[0];
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name)) {
            return "PlaybackMenuAction#" + actionId;
        }
        return null;
    }

    private void dispatch() {
        switch (actionId) {
            case OPEN_FOREGROUND_AUTO_NEXT:
                PlaybackMenuActions.openForegroundAutoNextSubMenu(context);
                break;
            case TOGGLE_FOREGROUND_AUTO_NEXT:
                PlaybackMenuActions.toggleForegroundAutoNext(toggleFlow);
                break;
            case OPEN_FOREGROUND_SCOPES:
                PlaybackMenuActions.openForegroundAutoNextScopes(context);
                break;
            case OPEN_FOREGROUND_PREFS:
                PlaybackMenuActions.openForegroundAutoNextPrefs(context);
                break;
            case OPEN_FOREGROUND_ORIENTATION:
                PlaybackMenuActions.openForegroundOrientationPicker(context);
                break;
            case OPEN_FOREGROUND_UP:
                PlaybackMenuActions.openForegroundUpPicker(context);
                break;
            case OPEN_FOREGROUND_TAG:
                PlaybackMenuActions.openForegroundTagPicker(context);
                break;
            case TOGGLE_STORY_BACKGROUND:
                PlaybackMenuActions.toggleStoryBackgroundAutoNext(toggleFlow);
                break;
            case TOGGLE_MEDIA_BUTTON:
                PlaybackMenuActions.toggleMediaButtonControl(toggleFlow);
                break;
            case TOGGLE_DISABLE_CHAPTER:
                PlaybackMenuActions.toggleDisableChapterProgress(toggleFlow);
                break;
            case BLOCK_STORY_CURRENT_UP:
                PlaybackMenuActions.blockCurrentStoryUp(context);
                break;
            default:
                break;
        }
    }
}
