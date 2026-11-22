package android.os;
import java.util.ArrayList;
import java.util.List;
public final class Handler {
    private final List<Runnable> queue=new ArrayList<>(),delayed=new ArrayList<>();
    public boolean post(Runnable task){queue.add(task);return true;}
    public boolean postDelayed(Runnable task,long millis){delayed.add(task);return true;}
    public void removeCallbacks(Runnable task){queue.remove(task);delayed.remove(task);}
    public void drain(){while(!queue.isEmpty())queue.remove(0).run();}
    public void timeout(){List<Runnable> tasks=new ArrayList<>(delayed);delayed.clear();for(Runnable task:tasks)task.run();}
}
