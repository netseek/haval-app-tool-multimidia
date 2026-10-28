import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(
    new URL('../source/v1.0/default/src/core/main.js', import.meta.url), 'utf8'
);

// Execute the real, dependency-free policy helpers and each real visibility block.
// The DOM and Android bridge are deliberately outside this bounded model.
function bracedBlock(start) {
    const open = source.indexOf('{', start);
    let depth = 1;
    let end = open + 1;
    for (; end < source.length && depth; end++) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0, 'source block is balanced');
    return source.slice(start, end);
}

const names = [
    'isProjectionMapDisplayActive', 'isProjectionCardOverlayActive',
    'isMainMenuDetailScreen', 'isMainMenuSessionScreen', 'isRightMenuVisible',
    'getEffectiveDisplayMode'
];
const helpers = names.map(name => {
    const start = source.indexOf(`function ${name}(`);
    assert.notEqual(start, -1, `real helper ${name} exists`);
    return bracedBlock(start);
}).join('\n');

const visibilityBlocks = [
    source.indexOf('if (menuWrapper)', source.indexOf('function render()')),
    source.indexOf('if (menuWrapper)', source.indexOf("subscribe('cardId',"))
].map(start => {
    assert.notEqual(start, -1, 'real caller visibility block exists');
    return bracedBlock(start);
});

function model(values) {
    const state = { ...values };
    const context = vm.createContext({
        get: key => state[key],
        cardId: state.cardId,
        screen: state.screen,
        menuWrapper: { style: {} }
    });
    vm.runInContext(helpers, context);
    return {
        context,
        visible: (card = state.cardId, screen = state.screen) =>
            context.isRightMenuVisible(card, screen),
        displays: () => visibilityBlocks.map(block => {
            context.menuWrapper.style.display = 'uninitialized';
            vm.runInContext(block, context);
            return context.menuWrapper.style.display;
        })
    };
}

const projectionKeys = ['carPlayInDash', 'projectionMirrorInDash', 'projectionPreparingD3'];
const menuScreens = ['main_menu', 'aircon', 'graph', 'graphs', 'regen', 'display_selection'];

test('bootstrap cards never authorize an overlay without the genuine boolean permission', () => {
    for (const projectionKey of projectionKeys) {
        for (const permission of [undefined, false, 'true', 1]) {
            for (const cardId of [0, 1, 3]) {
                const m = model({ [projectionKey]: true, projectionCardOverlayAllowed: permission,
                    cardId, screen: 'main_menu' });
                assert.equal(m.visible(), false);
                assert.deepEqual(m.displays(), ['none', 'none']);
            }
        }
    }
});

test('a physically authorized menu session survives neutral cards at both callers', () => {
    for (const projectionKey of projectionKeys) {
        for (const screen of menuScreens) {
            const m = model({ [projectionKey]: true, projectionCardOverlayAllowed: true,
                cardId: 0, screen });
            assert.equal(m.visible(), true);
            assert.deepEqual(m.displays(), ['block', 'block']);
        }
    }
});

test('permission cannot authorize non-menu screens or stale projection flags', () => {
    for (const screen of ['hidden', 'dashboard', 'warning', undefined]) {
        const m = model({ carPlayInDash: true, projectionCardOverlayAllowed: true,
            cardId: 1, screen });
        assert.equal(m.visible(), false);
        assert.deepEqual(m.displays(), ['none', 'none']);
    }
    const m = model({ carPlayInDash: 'true', projectionCardOverlayAllowed: true,
        cardId: 0, screen: 'main_menu' });
    assert.equal(m.visible(), false);
    assert.deepEqual(m.displays(), ['none', 'none']);
});

test('outside projection, existing nonzero-card visibility is preserved', () => {
    for (const cardId of [0, 1, 2, 3]) {
        const m = model({ cardId, screen: 'main_menu', projectionCardOverlayAllowed: false });
        assert.equal(m.visible(), cardId !== 0);
        const display = cardId === 0 ? 'none' : 'block';
        assert.deepEqual(m.displays(), [display, display]);
    }
});

test('render uses its explicit screen rather than a different state snapshot', () => {
    const m = model({ carPlayInDash: true, projectionCardOverlayAllowed: true,
        cardId: 0, screen: 'main_menu' });
    assert.equal(m.visible(0, 'warning'), false);
    assert.equal(m.visible(0, 'aircon'), true);
});

test('projection preserves the user-selected display mode', () => {
    for (const display of ['Normal', 'Reduzido', 'Clean', 'Mapa']) {
        const m = model({ carPlayInDash: true, display, navigationDisplayMode: 'Mapa' });
        assert.equal(m.context.getEffectiveDisplayMode(), display);
    }
});
