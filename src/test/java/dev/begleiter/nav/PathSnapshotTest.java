package dev.begleiter.nav;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathSnapshotTest {
    private Node node(int x, Node previous) {
        var node = new Node(x, 1, 0, 0); node.prev = previous;
        node.move = previous == null ? null : Move.TRAVERSE_E;
        node.g = previous == null ? 0 : previous.g + 4;
        return node;
    }
    @Test void publishedRouteDoesNotChangeWithSearchTree() {
        var root = node(0, null); var middle = node(1, root); var end = node(2, middle);
        var snapshot = Path.from(end, false);
        middle.g = 1; end.prev = root;
        assertEquals(2, snapshot.length());
        assertEquals(8, snapshot.totalCost());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.positions.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.moves.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.costs.clear());
    }
    @Test void joinedRouteHasOneSeamAndCorrectRemainingCosts() {
        var root = node(0, null); var middle = node(1, root);
        var first = Path.from(middle, false);
        var secondRoot = node(1, null); var second = Path.from(node(2, secondRoot), true);
        var joined = first.append(second);
        assertEquals(2, joined.length()); assertEquals(3, joined.positions.size());
        assertTrue(joined.complete); assertFalse(first.complete);
        assertEquals(8, joined.remainingCost(0)); assertEquals(4, joined.remainingCost(1));
        assertEquals(0, joined.remainingCost(2));
        assertEquals(1, joined.suffix(1).length());
    }
    @Test void disconnectedRoutesAreRejected() {
        var first = Path.from(node(1, node(0, null)), false);
        var next = Path.from(node(6, node(5, null)), true);
        assertThrows(IllegalArgumentException.class, () -> first.append(next));
    }
}
