import Testing
@testable import iosApp

/// LLM-free coverage of the Breakdown state machine (Deferno#525). Per the PRD's testing decisions we
/// assert **external behavior** — which moves run with what args, given a scripted sequence of classified
/// answers, and the terminal state — never internals. A `ScriptedClassifier` stands in for Apple
/// Intelligence and a `SpyMoves` records the offline-first moves, so the whole flow runs with no model.
@MainActor
struct BreakdownEngineTests {

    // MARK: Fakes

    /// Returns scripted classifications in call order (the engine is deterministic, so order is stable).
    actor ScriptedClassifier: ImpedimentClassifier {
        private var queue: [ImpedimentClassification]
        private let fallback: ImpedimentClassification
        init(_ queue: [ImpedimentClassification],
             fallback: ImpedimentClassification = .init(kind: .nothingStopping)) {
            self.queue = queue
            self.fallback = fallback
        }
        func classify(answer: String, item: ItemContext) async throws -> ImpedimentClassification {
            queue.isEmpty ? fallback : queue.removeFirst()
        }
    }

    /// A classifier that always fails — to assert the graceful "rephrase" recovery.
    struct FailingClassifier: ImpedimentClassifier {
        func classify(answer: String, item: ItemContext) async throws -> ImpedimentClassification {
            throw BreakdownClassifierError.generationFailed
        }
    }

    /// Records every move + mints child ids so recursion has something to descend into.
    actor SpyMoves: BreakdownMoves {
        private(set) var capturedParents: [String] = []
        private(set) var capturedTitles: [String] = []
        private(set) var dropped: [String] = []
        private(set) var planned: [String] = []
        private var n = 0
        func captureSubtask(under parentID: String, title: String) async -> String? {
            capturedParents.append(parentID); capturedTitles.append(title)
            n += 1; return "child-\(n)"
        }
        func drop(_ id: String) async { dropped.append(id) }
        func addToPlan(_ id: String) async { planned.append(id) }
    }

    private func engine(_ script: [ImpedimentClassification],
                        moves: SpyMoves,
                        title: String = "Clean the garage",
                        id: String = "root") -> BreakdownEngine {
        BreakdownEngine(root: ItemContext(id: id, title: title),
                        classifier: ScriptedClassifier(script),
                        moves: moves)
    }

    // MARK: Tests

    @Test func startsByAskingAboutTheRoot() {
        let e = engine([], moves: SpyMoves())
        #expect(e.phase == .asking)
        #expect(e.focusTitle == "Clean the garage")
        #expect(e.messages.last?.role == .assistant)
    }

    @Test func tooBigCapturesSubtasksUnderTheRootThenRecursesToReady() async {
        let spy = SpyMoves()
        let e = engine([
            .init(kind: .tooBig, subtaskTitles: ["Clear the workbench", "Sort the bins"]),
            .init(kind: .nothingStopping), // first child
            .init(kind: .nothingStopping), // second child
        ], moves: spy)

        await e.submit(answer: "it's just too big")
        await e.submit(answer: "nothing")
        await e.submit(answer: "nothing")

        #expect(e.phase == .finished(.ready))
        #expect(await spy.capturedTitles == ["Clear the workbench", "Sort the bins"])
        #expect(await spy.capturedParents == ["root", "root"])
        #expect(await spy.dropped.isEmpty)
    }

    @Test func dontKnowHowSpinsOffAPrerequisiteSubtask() async {
        let spy = SpyMoves()
        let e = engine([
            .init(kind: .dontKnowHow, prerequisiteTitle: "Research disposal options"),
            .init(kind: .nothingStopping), // the prerequisite child
        ], moves: spy)

        await e.submit(answer: "I don't know how")
        await e.submit(answer: "nothing")

        #expect(await spy.capturedTitles == ["Research disposal options"])
        #expect(await spy.capturedParents == ["root"])
        #expect(e.phase == .finished(.ready))
    }

    @Test func persistentAvoidanceConfirmedDropsTheItem() async {
        let spy = SpyMoves()
        let e = engine([.init(kind: .persistentAvoidance)], moves: spy)

        await e.submit(answer: "honestly I just never want to")
        #expect(e.phase == .confirmingDrop)

        await e.confirmDrop(true)
        #expect(e.phase == .finished(.dropped))
        #expect(await spy.dropped == ["root"])
    }

    @Test func persistentAvoidanceDeclinedKeepsTheItemAndReAsks() async {
        let spy = SpyMoves()
        let e = engine([.init(kind: .persistentAvoidance)], moves: spy)

        await e.submit(answer: "never want to")
        await e.confirmDrop(false)

        #expect(e.phase == .asking)
        #expect(await spy.dropped.isEmpty)
    }

    @Test func nothingStoppingIsReadyAndCanGoOnTodaysPlan() async {
        let spy = SpyMoves()
        let e = engine([.init(kind: .nothingStopping)], moves: spy)

        await e.submit(answer: "nothing really, I can start")
        #expect(e.phase == .finished(.ready))

        await e.addRootToPlan()
        #expect(await spy.planned == ["root"])
    }

    @Test func transientObstacleMakesNoStructuralChange() async {
        let spy = SpyMoves()
        let e = engine([.init(kind: .transientObstacle)], moves: spy)

        await e.submit(answer: "it's raining")

        #expect(e.phase == .finished(.ready))
        #expect(await spy.capturedTitles.isEmpty)
        #expect(await spy.dropped.isEmpty)
    }

    @Test func blockedAndUrgentDegradeWithoutMutatingTheGraph() async {
        for stuck in [ImpedimentClass.waitingOnDependency, .somethingMoreUrgent] {
            let spy = SpyMoves()
            let e = engine([.init(kind: stuck)], moves: spy)
            await e.submit(answer: "…")
            #expect(e.phase == .finished(.ready))
            #expect(await spy.capturedTitles.isEmpty)
            #expect(await spy.dropped.isEmpty)
            #expect(await spy.planned.isEmpty)
        }
    }

    @Test func aClassifierFailureLetsThePersonRephrase() async {
        let e = BreakdownEngine(root: ItemContext(id: "root", title: "X"),
                                classifier: FailingClassifier(),
                                moves: SpyMoves())
        await e.submit(answer: "uhh")
        #expect(e.phase == .asking)
    }

    @Test func bailEndsTheFlow() {
        let e = engine([], moves: SpyMoves())
        e.bail()
        #expect(e.phase == .finished(.bailed))
    }
}
