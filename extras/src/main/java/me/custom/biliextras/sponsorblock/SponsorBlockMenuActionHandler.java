package me.custom.biliextras.sponsorblock;

import android.content.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Host {@code Function0} proxy that dispatches menu actions by numeric id.
 * Avoids {@link Runnable} so R8 cannot merge unrelated click handlers.
 */
public final class SponsorBlockMenuActionHandler implements InvocationHandler {
    public static final int ACTION_OPEN_MAIN = 1;
    public static final int ACTION_TOGGLE_ENABLED = 2;
    public static final int ACTION_SHOW_SUBMIT = 3;
    public static final int ACTION_MANUAL_SKIP_CURRENT = 4;
    public static final int ACTION_SHOW_CURRENT_SEGMENT = 5;
    public static final int ACTION_OPEN_SEGMENT_LIST = 6;
    public static final int ACTION_REFETCH_WITH_TOAST = 7;
    public static final int ACTION_OPEN_SEGMENT_ACTIONS = 8;
    public static final int ACTION_MANUAL_SKIP_SEGMENT = 9;
    public static final int ACTION_SEEK_SEGMENT = 10;
    public static final int ACTION_VOTE_SEGMENT = 11;
    public static final int ACTION_CHANGE_CATEGORY = 12;

    private final int actionId;
    private final Context context;
    private final int segmentIndex;
    private final int voteType;
    private final Object toggleFlow;

    private SponsorBlockMenuActionHandler(
            int actionId,
            Context context,
            int segmentIndex,
            int voteType,
            Object toggleFlow
    ) {
        this.actionId = actionId;
        this.context = context;
        this.segmentIndex = segmentIndex;
        this.voteType = voteType;
        this.toggleFlow = toggleFlow;
    }

    public static Object create(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context
    ) {
        return bind(hostClassLoader, fn0Interface, actionId, context, 0, 0, null);
    }

    public static Object createIndexed(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context,
            int segmentIndex
    ) {
        return bind(hostClassLoader, fn0Interface, actionId, context, segmentIndex, 0, null);
    }

    public static Object createVote(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            Context context,
            int segmentIndex,
            int voteType
    ) {
        return bind(hostClassLoader, fn0Interface, ACTION_VOTE_SEGMENT, context, segmentIndex, voteType, null);
    }

    public static Object createToggle(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            Object checkedFlow
    ) {
        return bind(hostClassLoader, fn0Interface, ACTION_TOGGLE_ENABLED, null, 0, 0, checkedFlow);
    }

    private static Object bind(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context,
            int segmentIndex,
            int voteType,
            Object toggleFlow
    ) {
        HostFunction0Proxy.installHostUnit(fn0Interface);
        SponsorBlockMenuActionHandler handler = new SponsorBlockMenuActionHandler(
                actionId, context, segmentIndex, voteType, toggleFlow
        );
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
            return "SponsorBlockMenuAction#" + actionId;
        }
        return null;
    }

    private void dispatch() {
        switch (actionId) {
            case ACTION_OPEN_MAIN:
                SponsorBlockMenuActions.openMainSubMenu(context);
                break;
            case ACTION_TOGGLE_ENABLED:
                SponsorBlockMenuActions.toggleEnabled(toggleFlow);
                break;
            case ACTION_SHOW_SUBMIT:
                SponsorBlockMenuActions.showSubmitDialog(context);
                break;
            case ACTION_MANUAL_SKIP_CURRENT:
                SponsorBlockMenuActions.manualSkipCurrent(context);
                break;
            case ACTION_SHOW_CURRENT_SEGMENT:
                SponsorBlockMenuActions.showCurrentSegmentActions(context);
                break;
            case ACTION_OPEN_SEGMENT_LIST:
                SponsorBlockMenuActions.openSegmentList(context);
                break;
            case ACTION_REFETCH_WITH_TOAST:
                SponsorBlockMenuActions.refetchSegmentsWithToast();
                break;
            case ACTION_OPEN_SEGMENT_ACTIONS:
                SponsorBlockMenuActions.openSegmentActions(context, segmentIndex);
                break;
            case ACTION_MANUAL_SKIP_SEGMENT:
                SponsorBlockMenuActions.manualSkipSegment(context, segmentIndex);
                break;
            case ACTION_SEEK_SEGMENT:
                SponsorBlockMenuActions.seekToSegment(context, segmentIndex);
                break;
            case ACTION_VOTE_SEGMENT:
                SponsorBlockMenuActions.voteSegment(context, segmentIndex, voteType);
                break;
            case ACTION_CHANGE_CATEGORY:
                SponsorBlockMenuActions.changeSegmentCategory(context, segmentIndex);
                break;
            default:
                break;
        }
    }
}
