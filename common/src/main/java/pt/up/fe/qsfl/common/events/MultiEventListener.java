package pt.up.fe.qsfl.common.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import pt.up.fe.qsfl.common.messaging.Message;
import pt.up.fe.qsfl.common.model.Node;

public class MultiEventListener implements EventListener {

    private List<EventListener> eventListeners = new ArrayList<EventListener>();

    public MultiEventListener(EventListener... els) {
        Collections.addAll(eventListeners, els);
    }

    public void add(EventListener el) {
        eventListeners.add(el);
    }

    @Override
    public void endTransaction(String transactionName, boolean[] activity, boolean isError) {
        for (EventListener el : eventListeners) {
            el.endTransaction(transactionName, activity, isError);
        }
    }

    @Override
    public void addNode(int id, String name, Node.Type type, int parentId, int line) {
        for (EventListener el : eventListeners) {
            el.addNode(id, name, type, parentId, line);
        }
    }

    @Override
    public void addProbe(int id, int nodeId) {
        for (EventListener el : eventListeners) {
            el.addProbe(id, nodeId);
        }

    }

    @Override
    public void endSession() {
        for (EventListener el : eventListeners) {
            el.endSession();
        }
    }

    @Override
    public void handleMessage(Message message) {
        for (EventListener el : eventListeners) {
            el.handleMessage(message);
        }
    }

}
