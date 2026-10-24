package kr.ac.khu.graffiti.moderation;

/** Stands in for a check that is not built yet: checks nothing and says so ({@link CheckOutcome#SKIPPED}). */
final class StubCheck implements ModerationCheck {

    private final String name;

    StubCheck(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CheckResult check(ModerationInput input) {
        return CheckResult.skipped(name);
    }
}
