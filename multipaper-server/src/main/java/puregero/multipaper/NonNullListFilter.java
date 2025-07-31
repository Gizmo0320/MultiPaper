package puregero.multipaper;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

public class NonNullListFilter<E extends ItemStack> extends NonNullList<E> {
    public NonNullList<ItemStack> lastItems;
    public final boolean[] dirty;
    public final Player player;
    public final String name;
    public boolean isDirty;

    protected NonNullListFilter(List<E> delegate, @Nullable E initialElement, Player player, String name) {
        super(delegate, initialElement);
        this.lastItems = NonNullList.withSize(delegate.size(), initialElement);
        this.dirty = new boolean[delegate.size()];
        this.player = player;
        this.name = name;
    }

    public static <E extends ItemStack> NonNullListFilter<E> withSize(int size, E defaultValue, Player player, String name) {
        Validate.notNull(defaultValue);
        ItemStack[] objects = new ItemStack[size];
        Arrays.fill(objects, defaultValue);
        return new NonNullListFilter<E>(Arrays.asList((E[])objects), defaultValue, player, name);
    }

    public boolean markDirty() {
        if (MultiPaperInventoryHandler.markDirty(this)) {
            isDirty = true;
            return true;
        }
        return false;
    }

    public boolean markDirty(int i) {
        if (markDirty()) {
            dirty[i] = true;
            return true;
        }
        return false;
    }

    @Override
    public E set(int i, E object) {
        object.listeningComponent = this;
        markDirty(i);
        return super.set(i, object);
    }

    @Override
    public void add(int i, E object) {
        throw new UnsupportedOperationException("Assumption - you can't add to a fixed sized list");
    }

    @Override
    public E remove(int i) {
        throw new UnsupportedOperationException("Assumption - you can't remove from a fixed sized list");
    }

    @Override
    public void clear() {
        if (markDirty(0)) {
            Arrays.fill(dirty, true);
        }
        super.clear();
    }
}
