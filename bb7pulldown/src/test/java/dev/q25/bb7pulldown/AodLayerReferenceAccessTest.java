package dev.q25.bb7pulldown;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AodLayerReferenceAccessTest {
    public static class NativeHelperBase {
        private int[] ids = {7, 42, 13};
        public int[] getReferencedIds() { return ids.clone(); }
        public void setReferencedIds(int[] refs) { ids = refs.clone(); }
    }

    public static final class RealisticInheritedLayer extends NativeHelperBase {}
    public static final class MissingContract {}
    public static final class WrongSignature {
        public String getReferencedIds() { return "not ids"; }
        public void setReferencedIds(int[] ids) {}
    }
    public static final class WrongSetter {
        public int[] getReferencedIds() { return new int[]{1}; }
        public void setReferencedIds(String ids) {}
    }
    public static final class IgnoringSetter {
        public int[] getReferencedIds() { return new int[]{1, 2}; }
        public void setReferencedIds(int[] ids) {}
    }

    @Test public void acceptsVerifiedInheritedPublicApi() throws Exception {
        AodLayerReferenceAccess api =
                AodLayerReferenceAccess.inspect(RealisticInheritedLayer.class);
        assertNotNull(api);
        RealisticInheritedLayer layer = new RealisticInheritedLayer();
        assertTrue(api.replaceIfUnchanged(layer, new int[]{7, 42, 13},
                new int[]{7, 13}));
        assertArrayEquals(new int[]{7, 13}, api.read(layer));
    }

    @Test public void rejectsAbsentAndWrongApiWithoutMutation() {
        assertNull(AodLayerReferenceAccess.inspect(MissingContract.class));
        assertNull(AodLayerReferenceAccess.inspect(WrongSignature.class));
        assertNull(AodLayerReferenceAccess.inspect(WrongSetter.class));
    }

    @Test public void refusesEmptyOrStaleReferenceReplacement() throws Exception {
        AodLayerReferenceAccess api = AodLayerReferenceAccess.inspect(NativeHelperBase.class);
        NativeHelperBase layer = new NativeHelperBase();
        assertFalse(api.replaceIfUnchanged(layer, new int[]{7, 42, 13}, new int[0]));
        assertFalse(api.replaceIfUnchanged(layer, new int[]{42}, new int[]{13}));
        assertArrayEquals(new int[]{7, 42, 13}, api.read(layer));
    }

    @Test public void detectsSetterWithoutVerifiedEffect() throws Exception {
        AodLayerReferenceAccess api = AodLayerReferenceAccess.inspect(IgnoringSetter.class);
        assertFalse(api.replaceIfUnchanged(new IgnoringSetter(), new int[]{1, 2},
                new int[]{2}));
    }
}
