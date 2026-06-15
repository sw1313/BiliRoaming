package me.custom.biliextras.sponsorblock;

import android.content.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class SponsorBlockSubmitActionHandler implements InvocationHandler {
    public static final int OPEN_EXISTING = 1;
    public static final int OPEN_DRAFT = 2;
    public static final int ADD_DRAFT = 3;
    public static final int COMMIT = 4;
    /** subAction: 0=editStartMenu, 1=editEndMenu, 2=category, 3=remove, 4=addAnother */
    public static final int DRAFT_ACTION = 5;
    /** subAction: 0=start, 1=end, 2=category, 3=save, 4=downvote, 5=unvote, 6=upvote */
    public static final int EXISTING_ACTION = 6;
    /** draft time: subAction 0=current, 1=begin/end, 2=manual; timeField 0=start, 1=end */
    public static final int TIME_ACTION = 7;
    public static final int EXISTING_TIME = 8;
    /** subAction: 0=main, 1=draftEdit, 2=existingEdit */
    public static final int BACK = 9;

    private final int actionId;
    private final Context context;
    private final int index;
    private final int subAction;
    /** TIME_ACTION: 0=start, 1=end; EXISTING: unused */
    private final int timeField;

    private SponsorBlockSubmitActionHandler(
            int actionId,
            Context context,
            int index,
            int subAction,
            int timeField
    ) {
        this.actionId = actionId;
        this.context = context;
        this.index = index;
        this.subAction = subAction;
        this.timeField = timeField;
    }

    public static Object create(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context
    ) {
        return bind(hostClassLoader, fn0Interface, actionId, context, 0, 0, 0);
    }

    public static Object createIndexed(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context,
            int index
    ) {
        return bind(hostClassLoader, fn0Interface, actionId, context, index, 0, 0);
    }

    public static Object createSub(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context,
            int index,
            int subAction
    ) {
        return bind(hostClassLoader, fn0Interface, actionId, context, index, subAction, 0);
    }

    public static Object createBack(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            Context context,
            int subAction,
            int index
    ) {
        return bind(hostClassLoader, fn0Interface, BACK, context, index, subAction, 0);
    }

    public static Object createTime(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            Context context,
            int draftIndex,
            int timeField,
            int subAction
    ) {
        return bind(hostClassLoader, fn0Interface, TIME_ACTION, context, draftIndex, subAction, timeField);
    }

    public static Object createExistingTime(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            Context context,
            int existingIndex,
            int timeField,
            int subAction
    ) {
        return bind(
                hostClassLoader, fn0Interface, EXISTING_TIME, context, existingIndex, subAction, timeField
        );
    }

    private static Object bind(
            ClassLoader hostClassLoader,
            Class<?> fn0Interface,
            int actionId,
            Context context,
            int index,
            int subAction,
            int timeField
    ) {
        HostFunction0Proxy.installHostUnit(fn0Interface);
        SponsorBlockSubmitActionHandler handler = new SponsorBlockSubmitActionHandler(
                actionId, context, index, subAction, timeField
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
            return "SponsorBlockSubmitAction#" + actionId;
        }
        return null;
    }

    private void dispatch() {
        switch (actionId) {
            case OPEN_EXISTING:
                SponsorBlockSubmitSubMenu.INSTANCE.showExistingEdit(context, index);
                break;
            case OPEN_DRAFT:
                SponsorBlockSubmitSubMenu.INSTANCE.showDraftEdit(context, index);
                break;
            case ADD_DRAFT:
                SponsorBlockSubmitSubMenu.INSTANCE.addDraftAndRefresh(context);
                break;
            case COMMIT:
                SponsorBlockSubmitSubMenu.INSTANCE.commitDrafts(context);
                break;
            case DRAFT_ACTION:
                SponsorBlockSubmitSubMenu.INSTANCE.onDraftAction(context, index, subAction);
                break;
            case EXISTING_ACTION:
                SponsorBlockSubmitSubMenu.INSTANCE.onExistingAction(context, index, subAction);
                break;
            case TIME_ACTION:
                SponsorBlockSubmitSubMenu.INSTANCE.onTimeAction(context, index, timeField, subAction);
                break;
            case EXISTING_TIME:
                SponsorBlockSubmitSubMenu.INSTANCE.onExistingTimeAction(
                        context, index, timeField, subAction
                );
                break;
            case BACK:
                SponsorBlockSubmitSubMenu.INSTANCE.onBackAction(context, subAction, index);
                break;
            default:
                break;
        }
    }
}
