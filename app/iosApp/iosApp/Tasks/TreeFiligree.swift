import Deferno
import SwiftUI

// The signature "See the trees" connected-tree filigree (iOS twin of feature/tasks/ui `TreeAtoms.kt`,
// #231): a curvy rail of per-depth vertical spines + a rounded elbow that lands in the kind node, and a
// node that is the kind dot (leaf) or an accent fold-disc with a rotating chevron (parent). Drawn off the
// bridged `ItemRow` geometry (spine / depth / hasChildren / isExpanded) — no shared-component change.

enum TreeGeometry {
    /// Width of the leading rail+node region for a row at `depth` (columns 0…depth). Never narrower than
    /// the node's own footprint (`parentDisc`): a depth-0 row reserves only one gutter (22) but the node
    /// disc is 24 wide, and `.frame(width:)` wrapping content wider than the frame center-shifts the whole
    /// region ~1px left — which dragged the root's disc + descend line off the trunk its children draw
    /// (every deeper row's region is already ≥44, so they never shrink). Clamping to the node width keeps
    /// every depth's column on the same x.
    static func leadingWidth(depth: Int) -> CGFloat { max(CGFloat(depth + 1) * Tree.railGutter, Tree.parentDisc) }
    /// The x of the node column centre for a row at `depth`.
    static func nodeCenterX(depth: Int) -> CGFloat { CGFloat(depth) * Tree.railGutter + Tree.railGutter / 2 }
}

/// The connecting rail drawn behind a row's leading region. `spine[i]` flags whether the ancestor
/// through-line at gutter column `i` continues past this row (├ vs └ at the parent column). Sized by the
/// parent — use it as a `ZStack` underlay or `.background` spanning the row height.
struct TreeRail: View {
    let spine: [Bool]
    let depth: Int
    let hasChildren: Bool
    let isExpanded: Bool
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            guard depth > 0 || (hasChildren && isExpanded) else { return }
            let g = Tree.railGutter
            let midY = size.height / 2
            let stroke = Tree.railStroke
            let lineColor = color.opacity(0.5)
            let nodeRadius = (hasChildren ? Tree.parentDisc : Tree.leafDot) / 2
            func colX(_ i: Int) -> CGFloat { CGFloat(i) * g + g / 2 }
            let nodeX = TreeGeometry.nodeCenterX(depth: depth)

            func vline(_ x: CGFloat, _ y0: CGFloat, _ y1: CGFloat) {
                var p = Path(); p.move(to: CGPoint(x: x, y: y0)); p.addLine(to: CGPoint(x: x, y: y1))
                ctx.stroke(p, with: .color(lineColor), lineWidth: stroke)
            }

            for i in 0..<depth {
                let x = colX(i)
                let continues = i < spine.count ? spine[i] : false
                if i < depth - 1 {
                    if continues { vline(x, 0, size.height) }
                } else {
                    // The row's own column: draw the vertical spine ONCE — full-height when a sibling
                    // follows below (├), else only down to the elbow corner (└) — then start the elbow
                    // path at that corner. Drawing the vertical inside the elbow path too (as before)
                    // double-strokes the top half, making the spine read uneven in weight. Mirrors the
                    // Compose twin (TreeAtoms.treeRail).
                    let r: CGFloat = 8
                    let corner = midY - r
                    vline(x, 0, continues ? size.height : corner)
                    var elbow = Path()
                    elbow.move(to: CGPoint(x: x, y: corner))
                    elbow.addQuadCurve(to: CGPoint(x: x + r, y: midY), control: CGPoint(x: x, y: midY))
                    elbow.addLine(to: CGPoint(x: nodeX - nodeRadius, y: midY))
                    ctx.stroke(elbow, with: .color(lineColor), lineWidth: stroke)
                }
            }

            // Drop a line from this node into its expanded subtree.
            if hasChildren && isExpanded {
                vline(nodeX, midY + nodeRadius, size.height)
            }
        }
        .allowsHitTesting(false)
    }
}

/// The kind node: a small solid kind dot for a leaf, or an accent-tinted fold-disc holding a rotating
/// chevron for a parent (the tappable fold control). Sized to a uniform footprint so titles align.
struct TreeNode: View {
    let kindColor: Color
    let hasChildren: Bool
    let isExpanded: Bool
    let onToggle: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Group {
            if hasChildren {
                Button(action: onToggle) {
                    ZStack {
                        Circle().fill(kindColor.opacity(0.18))
                        Circle().strokeBorder(colors.surface, lineWidth: 2)
                        DefernoIcon.chevronRight.image(size: 10, weight: .semibold)
                            .foregroundStyle(kindColor)
                            .rotationEffect(.degrees(isExpanded ? 90 : 0))
                    }
                    .frame(width: Tree.parentDisc, height: Tree.parentDisc)
                    .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isExpanded ? "Collapse" : "Expand")
            } else {
                ZStack {
                    Circle().fill(colors.surface).frame(width: Tree.leafDot + 4, height: Tree.leafDot + 4)
                    KindDot(color: kindColor)
                }
                .frame(width: Tree.parentDisc, height: Tree.parentDisc)
                .accessibilityHidden(true)
            }
        }
    }
}
