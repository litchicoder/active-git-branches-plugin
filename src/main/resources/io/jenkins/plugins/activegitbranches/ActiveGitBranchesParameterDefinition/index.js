function initializeActiveGitBranchesParameter(root) {
    if (!root || root.dataset.agbInitialized === "true") {
        return;
    }

    var select = root.querySelector("select.agb-select");
    var wrapper = root.querySelector(".agb-custom-wrapper");
    if (!select || !wrapper) {
        return;
    }

    root.dataset.agbInitialized = "true";
    var input = wrapper.querySelector("input.agb-custom-input");

    function updateCustomBranchInput() {
        var isCustom = select.value === "__custom__";
        wrapper.hidden = !isCustom;
        if (input) {
            input.disabled = !isCustom;
            if (isCustom) {
                setTimeout(function() {
                    input.focus();
                }, 0);
            }
        }
    }

    select.addEventListener("change", updateCustomBranchInput);
    updateCustomBranchInput();
}

function initializeActiveGitBranchesParameters() {
    document.querySelectorAll(".active-git-branches-parameter").forEach(function(root) {
        initializeActiveGitBranchesParameter(root);
    });
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initializeActiveGitBranchesParameters);
} else {
    initializeActiveGitBranchesParameters();
}
