package moe.caa.multilogin.flows.workflows;

import lombok.Getter;
import moe.caa.multilogin.flows.FlowContext;
import moe.caa.multilogin.flows.ProcessingFailedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 代表一个并行的委托流
 * 所有工序并行尝试加工这个零件，直到有一条工序能顺利完成。
 */
public class EntrustFlows<C extends FlowContext> implements IFlows<C> {
    @Getter
    private final List<IFlows<C>> steps;

    public EntrustFlows() {
        this(new ArrayList<>());
    }

    public EntrustFlows(List<IFlows<C>> steps) {
        this.steps = (steps);
    }

    @Override
    public C run(C context) {
        // 信号
        CountDownLatch latch = new CountDownLatch(1);
        // 存放执行成功的工序
        AtomicReference<C> passed = new AtomicReference<>();
        // 存放当前有多少工序加工
        List<IFlows<C>> currentTasks = Collections.synchronizedList(new ArrayList<>());
        // 避免阻死
        boolean flag = false;
        for (IFlows<C> step : steps) {
            flag = true;
            currentTasks.add(step);
            FlowContext.getExecutorService().execute(() -> {
                try {
                    // 工序复制品
                    final C run = step.run((C) context.clone());
                    // 这个工序能完成这项任务，释放信号
                    if (run.getSignal() == FlowContext.Signal.PASS) {
                        passed.set(run);
                        latch.countDown();
                    }
                } finally {
                    currentTasks.remove(step);
                    // 没人能完成这个工序，释放信号
                    if (currentTasks.isEmpty()) latch.countDown();
                }
            });
        }

        if (flag) try {
            latch.await();
        } catch (InterruptedException e) {
            throw new ProcessingFailedException(name(), e);
        }

        final FlowContext flowContext = passed.get();
        if (flowContext != null) return (C) flowContext;
        // TODO: 2022/2/14 ERROR 信号
        // 委托不能完成，终止
        return (C) context.clone().setSignal(FlowContext.Signal.TERMINATE);
    }

    @Override
    public String name() {
        return "EntrustFlows";
    }
}
