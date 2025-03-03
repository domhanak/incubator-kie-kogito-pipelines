def resolveRepository(String repository, String author, String branches, boolean ignoreErrors, String credentialID = 'kie-ci') {
    println "[INFO] Resolving Repository https://github.com/${author}/${repository}:${branches}. CredentialsID: ${credentialID}"
    return [$class                           : 'GitSCM',
            branches                         : [[name: branches]],
            doGenerateSubmoduleConfigurations: false,
            extensions                       : [[$class: 'CleanBeforeCheckout'],
                                                [$class             : 'SubmoduleOption',
                                                 disableSubmodules  : false,
                                                 parentCredentials  : true,
                                                 recursiveSubmodules: true,
                                                 reference          : '',
                                                 trackingSubmodules : false],
                                                [$class           : 'RelativeTargetDirectory',
                                                 relativeTargetDir: './']],
            submoduleCfg                     : [],
            userRemoteConfigs                : [[credentialsId: credentialID, url: "https://github.com/${author}/${repository}.git"]]
    ]
}

def checkoutIfExists(String repository, String author, String branches, String defaultAuthor, String defaultBranches, boolean mergeTarget = false, def credentials = ['token': 'kie-ci1-token', 'usernamePassword': 'kie-ci']) {
    assert credentials['token']
    assert credentials['usernamePassword']
    def sourceAuthor = author
    def sourceRepository = getForkedProjectName(defaultAuthor, repository, sourceAuthor, credentials['token']) ?: repository
    // Checks source group and branch (for cases where the branch has been created in the author's forked project)
    def repositoryScm = getRepositoryScm(sourceRepository, author, branches, credentials['usernamePassword'])
    if (repositoryScm == null) {
        // Checks target group and and source branch (for cases where the branch has been created in the target project itself
        repositoryScm = getRepositoryScm(repository, defaultAuthor, branches, credentials['usernamePassword'])
        sourceAuthor = repositoryScm ? defaultAuthor : author
    }
    if (repositoryScm != null && (!mergeTarget || hasPullRequest(defaultAuthor, repository, author, branches, credentials['token']))) {
        if (mergeTarget) {
            mergeSourceIntoTarget(sourceRepository, sourceAuthor, branches, repository, defaultAuthor, defaultBranches, credentials['usernamePassword'])
        } else {
            checkout repositoryScm
        }
    } else {
        checkout(resolveRepository(repository, defaultAuthor, defaultBranches, false, credentials['usernamePassword']))
    }
}

def getRepositoryScm(String repository, String author, String branches, String credentialId = 'kie-ci') {
    def repositoryScm = resolveRepository(repository, author, branches, true, credentialId)
    dir("githubscm-get-repository-${repository}") {
        try {
            checkout repositoryScm
        } catch (Exception ex) {
            println "[WARNING] Branches [${branches}] from repository ${repository} not found in ${author} organisation."
            repositoryScm = null
        } finally {
            deleteDir()
        }
    }
    return repositoryScm
}

def mergeSourceIntoTarget(String sourceRepository, String sourceAuthor, String sourceBranches, String targetRepository, String targetAuthor, String targetBranches, String credentialId = 'kie-ci') {
    println "[INFO] Merging source [${sourceAuthor}/${sourceRepository}:${sourceBranches}] into target [${targetAuthor}/${targetRepository}:${targetBranches}]..."
    checkout(resolveRepository(targetRepository, targetAuthor, targetBranches, false, credentialId))
    setUserConfigFromCreds(credentialId)
    def targetCommit = getCommit()

    try {
        withCredentials([usernameColonPassword(credentialsId: credentialId, variable: 'kieCiUserPassword')]) {
            sh "git pull https://${kieCiUserPassword}@github.com/${sourceAuthor}/${sourceRepository} ${sourceBranches}"
        }
    } catch (Exception e) {
        println """
        -------------------------------------------------------------
        [ERROR] Can't merge source into Target. Please rebase PR branch.
        -------------------------------------------------------------
        Source: git://github.com/${sourceAuthor}/${sourceRepository} ${sourceBranches}
        Target: ${targetCommit}
        -------------------------------------------------------------
        """
        throw e
    }
    def mergedCommit = getCommit()

    println """
    -------------------------------------------------------------
    [INFO] Source merged into Target
    -------------------------------------------------------------
    Target: ${targetCommit}
    Produced: ${mergedCommit}
    -------------------------------------------------------------
    """
}

def createBranch(String branchName) {
    try {
        sh "git checkout -b ${branchName}"
    } catch (Exception e) {
        println "[ERROR] Can't create branch ${branchName} on repo."
        throw e
    }
    println "[INFO] Created branch '${branchName}' on repo."
}

boolean isBranchExist(String remote, String branch) {
    sh "git fetch ${remote}"
    return sh(returnStatus: true, script: "git rev-parse ${remote}/${branch}") == 0
}

/*
* Remove a branch from the remote
*
* You need correct rights to delete the remote tag
*
* Will fail if the branch does not exist
*/
def removeRemoteBranch(String remote, String branch, String credentialsId = 'kie-ci') {
    pushObject("--delete ${remote}", "${branch}", credentialsId)
    println "[INFO] Deleted remote branch ${branch}."
}

void removeLocalBranch(String branch) {
    sh "git branch -D ${branch}"
    println "[INFO] Deleted branch ${branch}."
}

def commitChanges(String commitMessage, Closure preCommit) {
    preCommit()
    sh "git commit -m '${commitMessage}'"
}

def commitChanges(String commitMessage, String filesToAdd = '-u') {
    commitChanges(commitMessage, { sh "git add ${filesToAdd}" })
}

def addRemote(String remoteName, String remoteUrl) {
    sh "git remote add ${remoteName} ${remoteUrl}"
}

def squashCommits(String baseBranch, String newCommitMsg) {
    String branchName = sh(returnStdout: true, script: 'git rev-parse --abbrev-ref HEAD').trim()
    String mergeName = sh(returnStdout: true, script: "git merge-base ${baseBranch} ${branchName}").trim()
    sh "git reset ${mergeName}"
    sh 'git add -A'
    sh "git commit -m \"${newCommitMsg}\""
}

def forkRepo(String credentialID = 'kie-ci') {
    cleanHubAuth()
    withCredentials([usernamePassword(credentialsId: credentialID, usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN')]) {
        setUserConfig("${GITHUB_USER}")
        sh 'git config hub.protocol https'
        sh 'hub fork --remote-name=origin'
        sh 'git remote -v'
    }
}

def createPR(String pullRequestTitle, String pullRequestBody = '', String targetBranch = 'main', String credentialID = 'kie-ci') {
    def pullRequestLink
    try {
        pullRequestLink = executeHub("hub pull-request -m '${pullRequestTitle}' -m '${pullRequestBody}' -b '${targetBranch}'", credentialID)
    } catch (Exception e) {
        println "[ERROR] Unable to create PR. Please make sure the targetBranch ${targetBranch} is correct."
        throw e
    }
    println "Please see the created PR at: ${pullRequestLink}"
    return pullRequestLink
}

def createPrAsDraft(String pullRequestTitle, String pullRequestBody = '', String targetBranch = 'main', String credentialID = 'kie-ci') {
    def pullRequestLink
    try {
        pullRequestLink = executeHub("hub pull-request -d -m '${pullRequestTitle}' -m '${pullRequestBody}' -b '${targetBranch}'", credentialID)
    } catch (Exception e) {
        println "[ERROR] Unable to create Draft PR. Please make sure the targetBranch ${targetBranch} is correct."
        throw e
    }
    println "Please see the created Draft PR at: ${pullRequestLink}"
    return pullRequestLink
}

def createPRWithLabels(String pullRequestTitle, String pullRequestBody = '', String targetBranch = 'main', String[] labels, String credentialID = 'kie-ci') {
    def pullRequestLink
    try {
        pullRequestLink = executeHub("hub pull-request -m '${pullRequestTitle }' -m '${pullRequestBody }' -b '${targetBranch }' -l ${labels.collect { it -> "'${it }'" }.join(',')}", credentialID)
    } catch (Exception e) {
        println "[ERROR] Unable to create PR. Please make sure the targetBranch ${targetBranch} is correct."
        throw e
    }
    println "Please see the created PR at: ${pullRequestLink}"
    return pullRequestLink
}

def executeHub(String hubCommand, String credentialID = 'kie-ci') {
    cleanHubAuth()
    withCredentials([usernamePassword(credentialsId: credentialID, usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN')]) {
        setUserConfig("${GITHUB_USER}")
        return sh(returnStdout: true, script: hubCommand).trim()
    }
}

def mergePR(String pullRequestLink, String credentialID = 'kie-ci') {
    cleanHubAuth()
    withCredentials([usernamePassword(credentialsId: credentialID, usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN')]) {
        try {
            setUserConfig("${GITHUB_USER}")
            sh "hub merge ${pullRequestLink}"
        } catch (Exception e) {
            println "[ERROR] Can't merge PR ${pullRequestLink} on repo."
            throw e
        }
        println "[INFO] Merged PR '${pullRequestLink}' on repo."
    }
}

// Optional: Pass in env.BUILD_TAG as buildTag in pipeline script
// to trace back the build from which this tag came from.
def tagRepository(String tagName, String buildTag = '') {
    def currentCommit = getCommit()
    def tagMessageEnding = buildTag ? " in build \"${buildTag}\"." : '.'
    def tagMessage = "Tagged by Jenkins${tagMessageEnding}"
    sh "git tag -a '${tagName}' -m '${tagMessage}'"
    println """
-------------------------------------------------------------
[INFO] Tagged current repository
-------------------------------------------------------------
Commit: ${currentCommit}
Tagger: ${env.GIT_COMMITTER_NAME} (${env.GIT_COMMITTER_EMAIL})
Tag: ${tagName}
Tag Message: ${tagMessage}
-------------------------------------------------------------
"""
}

/*
* Push a tag to the remote
*
* You need correct rights to create the tag
*/

def pushRemoteTag(String remote, String tagName, String credentialsId = 'kie-ci') {
    pushObject(remote, "--tags ${tagName}", credentialsId)
    println "[INFO] Pushed remote tag ${tagName}."
}

boolean isTagExist(String remote, String tagName) {
    sh "git fetch ${remote} --tags"
    return sh(returnStatus: true, script: "git rev-parse ${tagName}") == 0
}

void removeLocalTag(String tagName) {
    sh "git tag -d ${tagName}"
    println "[INFO] Deleted tag ${tagName}."
}

/*
* Remove a tag from the remote
*
* You need correct rights to delete the remote tag
*
* Will fail if the tag does not exist
*/

def removeRemoteTag(String remote, String tagName, String credentialsId = 'kie-ci') {
    pushObject("--delete ${remote}", "${tagName}", credentialsId)
    println "[INFO] Deleted remote tag ${tagName}."
}

/*
* Creates a new release on GitHub
*/
void createRelease(String tagName, String buildBranch, String description = "Release ${tagName}", String credentialsId = 'kie-ci') {
    withCredentials([usernamePassword(credentialsId: "${credentialsId}", usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        sh "gh release create ${tagName} --target ${buildBranch} --title ${tagName} --notes \"${description}\""
    }
}

/*
* Creates a new release on GitHub with release notes
*/
void createReleaseWithReleaseNotes(String tagName, String buildBranch, String releaseNotes = 'Release Notes', String credentialsId = 'kie-ci') {
    withCredentials([usernamePassword(credentialsId: "${credentialsId}", usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        sh "gh release create ${tagName} --target ${buildBranch} --title ${tagName} -F ${releaseNotes}"
    }
}

/*
* Creates a new release on GitHub with GH generated release notes
*/
void createReleaseWithGeneratedReleaseNotes(String tagName, String buildBranch, String previousTag, String credentialsId = 'kie-ci') {
    withCredentials([usernamePassword(credentialsId: "${credentialsId}", usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        sh "gh release create ${tagName} --target ${buildBranch} --title ${tagName} --generate-notes --notes-start-tag ${previousTag}"
    }
}

/*
* Removes a release on GitHub
*/
void deleteRelease(String tagName, String credentialsId = 'kie-ci') {
    withCredentials([usernamePassword(credentialsId: "${credentialsId}", usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        sh "gh release delete ${tagName} -y"
    }
}

/*
* Removes a release and its tag on GitHub
*/
void deleteReleaseAndTag(String tagName, String credentialsId = 'kie-ci') {
    withCredentials([usernamePassword(credentialsId: "${credentialsId}", usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        sh "gh release delete --cleanup-tag ${tagName} -y"
    }
}

/*
* Checks whether a release exists on GitHub
*/
boolean isReleaseExist(String tagName, String credentialsId = 'kie-ci') {
    withCredentials([usernamePassword(credentialsId: "${credentialsId}", usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        // checks if the release is already existing
        exist = sh(script: "gh release view ${tagName}", returnStatus: true) == 0
    }
    return exist
}

/*
* Tag Local and remote repository
*
* You need correct rights to create or delete (in case of override) the tag
*/

def tagLocalAndRemoteRepository(String remote, String tagName, String credentialsId = 'kie-ci', String buildTag = '', boolean override = false) {
    if (override && isTagExist(remote, tagName)) {
        println "[INFO] Tag ${tagName} exists... Overriding it."
        removeLocalTag(tagName)
        removeRemoteTag(remote, tagName, credentialsId)
    }

    tagRepository(tagName, buildTag)
    pushRemoteTag(remote, tagName, credentialsId)
}

def pushObject(String remote, String object, String credentialsId = 'kie-ci') {
    try {
        withCredentials([usernamePassword(credentialsId: "${credentialsId}", usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN')]) {
            setUserConfig("${GITHUB_USER}")
            sh("git config --local credential.helper \"!f() { echo username=\\$GITHUB_USER; echo password=\\$GITHUB_TOKEN; }; f\"")
            sh("git push ${remote} ${object}")
        }
    } catch (Exception e) {
        println "[ERROR] Couldn't push object '${object}' to ${remote}."
        throw e
    }
    println "[INFO] Pushed object '${object}' to ${remote}."
}

def setUserConfig(String username, String domain = 'jenkins.kie.apache.org', boolean global=false) {
    sh "git config ${(global?'--global ':'')}user.email ${username}@${domain}"
    sh "git config ${(global?'--global ':'')}user.name ${username}"
}

def setUserConfigFromCreds(String credentialsId = 'kie-ci') {
    withCredentials([usernamePassword(credentialsId: "${credentialsId}", usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_TOKEN')]) {
        setUserConfig("${GITHUB_USER}")
    }
}

def getCommit() {
    return sh(returnStdout: true, script: 'git log --oneline -1').trim()
}

def getCommitHash() {
    return sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
}

String getTagCommitHash(String tagName) {
    return sh(returnStdout: true, script: "git rev-list -n 1 ${tagName}").trim()
}

/*
* Retrieve the Git repository URL from current dir
*/
def getGitRepositoryURL() {
    return sh(returnStdout: true, script: 'git config --get remote.origin.url | head -n 1').trim()
}

def getGitRepositoryName() {
    return sh(returnStdout: true, script: "basename ${getGitRepositoryURL()} | sed 's|\\.git||g'").trim()
}

def getGitRepositoryAuthor() {
    return sh(returnStdout: true, script: "echo ${getGitRepositoryURL()} | sed 's|/${getGitRepositoryName()}.*||g' | sed 's|.*github.com.\\?||g'").trim()
}

def getBranch() {
    return sh(returnStdout: true, script: 'git branch --all --contains HEAD').trim()
}

def getRemoteInfo(String remoteName, String configName) {
    return sh(returnStdout: true, script: "git config --get remote.${remoteName}.${configName}").trim()
}

def hasPullRequest(String group, String repository, String author, String branch, String credentialsId = 'kie-ci1-token') {
    return hasForkPullRequest(group, repository, author, branch, credentialsId) || hasOriginPullRequest(group, repository, branch, credentialsId)
}

def hasOriginPullRequest(String group, String repository, String branch, String credentialsId = 'kie-ci1-token') {
    return hasForkPullRequest(group, repository, group, branch, credentialsId)
}

def hasForkPullRequest(String group, String repository, String author, String branch, String credentialsId = 'kie-ci1-token') {
    def result = false
    withCredentials([string(credentialsId: credentialsId, variable: 'OAUTHTOKEN')]) {
        def curlResult = sh(returnStdout: true, script: "curl --globoff -H \"Authorization: token ${OAUTHTOKEN}\" 'https://api.github.com/repos/${group}/${repository}/pulls?head=${author}:${branch}&state=open'")?.trim()
        if (curlResult) {
            def pullRequestJsonObject = readJSON text: curlResult
            result = pullRequestJsonObject.size() > 0
        }
    }
    println "[INFO] has pull request for ${group}/${repository}:${author}:${branch} -> ${result}"
    return result
}

def getForkedProjectName(String group, String repository, String owner, String credentialsId = 'kie-ci1-token', int page = 1, int perPage = 100, replays = 3) {
    if (group == owner) {
        return repository
    }
    def result = null
    withCredentials([string(credentialsId: credentialsId, variable: 'OAUTHTOKEN')]) {
        def forkedProjects = null

        def curlResult = sh(returnStdout: true, script: "curl -H \"Authorization: token ${OAUTHTOKEN}\" 'https://api.github.com/repos/${group}/${repository}/forks?per_page=${perPage}&page=${page}'")?.trim()
        if (curlResult) {
            forkedProjects = readJSON text: curlResult
        }
        if (result == null && forkedProjects != null && forkedProjects.size() > 0) {
            try {
                def forkedProject = forkedProjects.find { it.owner.login == owner }
                result = forkedProject ? forkedProject.name : getForkedProjectName(group, repository, owner, credentialsId, ++page, perPage)
            } catch (MissingPropertyException e) {
                if (--replays <= 0) {
                    throw new Exception("Error getting forked project name for ${group}/${repository}/forks?per_page=${perPage}&page=${page}. Communication error, please relaunch job.")
                } else {
                    println("[ERROR] Getting forked project name for ${group}/${repository}/forks?per_page=${perPage}&page=${page}. Replaying... [${replays}]")
                    result = getForkedProjectName(group, repository, owner, credentialsId, page, perPage, replays)
                }
            }
        }
    }
    return result
}

def cleanHubAuth() {
    sh 'rm -rf ~/.config/hub'
}

def cleanWorkingTree() {
    sh 'git clean -xdf'
}

/**
 * Uses `find` command to stage all files matching the pattern and which are not in .gitignore
 */
def findAndStageNotIgnoredFiles(String findNamePattern) {
    // based on https://stackoverflow.com/a/59888964/8811872
    sh """
    find . -type f -name '${findNamePattern}' > found_files.txt
    files_to_add=""
    while IFS= read -r file; do
        if ! git check-ignore -q "\$file"; then
            files_to_add="\$files_to_add \$file"
        fi
    done < found_files.txt
    rm found_files.txt
    if [ ! -z "\$files_to_add" ]; then
        git add \$files_to_add
    fi
    git status
    """
}

boolean isThereAnyChanges() {
    return sh(script: 'git status --porcelain', returnStdout: true).trim() != ''
}

def updateReleaseBody(String tagName, String credsId = 'kie-ci') {
    String releaseNotesFile = 'release_notes'
    withCredentials([usernamePassword(credentialsId: credsId, usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        sh "gh release view ${tagName} --json body --jq .body > ${releaseNotesFile}"

        sh """
            #!/bin/bash
            sed -i -r 's|\\[((incubator-)?kie-issues[-#][0-9]*)\\](.*)|\\1\\3|g' ${releaseNotesFile}
            sed -i -r 's|(incubator-)?kie-issues[-#]([0-9]*)(.*)|\\[kie-issues#\\2\\](https\\://github\\.com/apache/incubator-kie-issues/issues/\\2)\\3|g' ${releaseNotesFile}
        """
        sh "gh release edit ${tagName} -F ${releaseNotesFile}"
    }
}

/*
* DEPRECATED
*
* Should use `getLatestTag` method instead which is more flexible
*/
@Deprecated
def getPreviousTag(String ignoreTag) {
    String latestTag = sh(returnStdout: true, script: 'git tag --sort=-taggerdate | head -n 1').trim()
    if (latestTag == ignoreTag) {
        latestTag = sh(returnStdout: true, script: 'git tag --sort=-taggerdate | head -n 2 | tail -n 1').trim()
    }
    echo "Got latestTag = ${latestTag}"
    return latestTag
}

def getLatestTag(String startsWith = '', String endsWith = '', List ignoreTags = []) {
    String cmd = 'git tag --sort=-taggerdate'
    cmd += ignoreTags.collect { tag -> " | grep -v '${tag}'" }.join('')
    if (startsWith) {
        cmd += " | grep '^${startsWith}'"
    }
    if (endsWith) {
        cmd += " | grep '${endsWith}\$'"
    }
    cmd += ' | head -n 1'
    return sh(returnStdout: true, script: cmd).trim()
}

def getPreviousTagFromVersion(String version, String startsWith = '', String endsWith = '', List filterOutGrep = [], boolean debug = false) {
    if (debug) { println "getPreviousTagFromVersion for version = ${version}" }
    String cmd = 'git tag --sort=-committerdate'
    if (endsWith) {
        cmd += " | grep '${endsWith}\$'"
    }
    if (filterOutGrep) {
        cmd += " ${filterOutGrep.collect { "| grep -v '${it}'" }.join(' ')}"
    }
    Integer[] versionSplit = util.parseVersion(version)

    Closure searchTag = { tagToSearch, reverse ->
        if (debug) { println "Searching tag ${tagToSearch}" }
        String foundTag = sh(returnStdout: true, script: "${cmd} | grep '${tagToSearch}' | sort -V${reverse ? ' -r' : ''}")?.trim()
        if (debug) { println "Found tag ${foundTag}" }
        return foundTag ? foundTag.split('\n')[0] : ''
    }

    // Previous micro search
    int micro = versionSplit[2]
    while (micro-- > 0) {
        String foundTag = searchTag("^${startsWith}${versionSplit[0]}.${versionSplit[1]}.${micro}", true)
        if (foundTag) { return foundTag }
    }

    // Previous minor search
    int minor = versionSplit[1]
    while (minor-- > 0) {
        String foundTag = searchTag("^${startsWith}${versionSplit[0]}.${minor}.", false)
        if (foundTag) { return foundTag }
    }

    // Previous major search (different looking for)
    int major = versionSplit[0]
    while (major-- > 0) {
        String foundTag = searchTag("^${startsWith}${major}.", true)
        if (foundTag) { return foundTag }
    }

    return ''
}

/*
* Store in env the commit info needed to update the commit status
*/
void prepareCommitStatusInformation(String repository, String author, String branch, String credentialsId = 'kie-ci') {
    dir("githubscm-prepare-commit-${repository}") {
        try {
            checkout(resolveRepository(repository, author, branch, false, credentialsId))
            setCommitStatusRepoURLEnv(repository)
            setCommitStatusShaEnv(repository)
        } finally {
            deleteDir()
        }
    }
}

/*
* Store in env the commit info needed to update the commit status of a PR
*/
void prepareCommitStatusInformationForPullRequest(String repository, String author, String branch, String targetAuthor, String credentialsId = 'kie-ci') {
    prepareCommitStatusInformation(repository, author, branch, credentialsId)
    setCommitStatusRepoURLEnv(repository, "https://github.com/${targetAuthor}/${repository}")
}

String getCommitStatusRepoURLEnv(String repository) {
    return env."${repository.toUpperCase()}_COMMIT_STATUS_REPO_URL"
}

void setCommitStatusRepoURLEnv(String repository, String url = '') {
    env."${repository.toUpperCase()}_COMMIT_STATUS_REPO_URL" = url ?: getGitRepositoryURL()
}

String getCommitStatusShaEnv(String repository) {
    return env."${repository.toUpperCase()}_COMMIT_STATUS_SHA"
}

void setCommitStatusShaEnv(String repository, String sha = '') {
    env."${repository.toUpperCase()}_COMMIT_STATUS_SHA" = sha ?: getCommitHash()
}

/*
* UpdateGithubCommitStatus for the given repository
*
* (Run `prepareCommitStatusInformation` before if you need to set specific commit info before updating. Useful when working with `checkoutIfExists`)
*
*   @params checkName       Name of the check to appear into GH check status page
*   @params state           State of the check: 'PENDING' / 'SUCCESS' / 'ERROR' / 'FAILURE'
*   @params message         Message to display next to the check
*/
def updateGithubCommitStatus(String checkName, String state, String message, String repository = '') {
    println "[INFO] Update commit status for check ${checkName}: state = ${state} and message = ${message}"

    if (!repository) {
        println '[INFO] No given repository... Trying to guess it from current directory'
        repository = getGitRepositoryName()
    }
    println "[DEBUG] repository name = ${repository}"

    if (!getCommitStatusRepoURLEnv(repository) || !getCommitStatusShaEnv(repository)) {
        println '[INFO] Commit status info are not stored, guessing from current repository'
        setCommitStatusRepoURLEnv(repository)
        setCommitStatusShaEnv(repository)
    }
    println "[DEBUG] repo url = ${getCommitStatusRepoURLEnv(repository)}"
    println "[DEBUG] commit sha = ${getCommitStatusShaEnv(repository)}"

    try {
        step([
            $class: 'GitHubCommitStatusSetter',
            commitShaSource: [$class: 'ManuallyEnteredShaSource', sha: getCommitStatusShaEnv(repository)],
            contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: checkName],
            reposSource: [$class: 'ManuallyEnteredRepositorySource', url: getCommitStatusRepoURLEnv(repository)],
            statusResultSource: [ $class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: message, state: state]] ],
        ])
    } catch(err) {
        println "Error updating commit status: ${err}"
    }
}

def updateGithubCommitStatusFromBuildResult(String checkName) {
    println "[INFO] Update commit status for check ${checkName} from build result"
    String buildResult = currentBuild.currentResult
    println "[DEBUG] Got build result ${buildResult}"

    def testResults = util.retrieveTestResults()
    println "[DEBUG] Got test results ${testResults}"
    String testsInfo = testResults ? "${testResults.passCount + testResults.skipCount + testResults.failCount} tests run, ${testResults.failCount} failed, ${testResults.skipCount} skipped." : 'No test results found.'

    int jobDuration = util.getJobDurationInSeconds()
    println "[DEBUG] Got job duration ${jobDuration} seconds"
    String timeInfo = util.displayDurationFromSeconds(jobDuration)

    switch (buildResult) {
        case 'SUCCESS':
            updateGithubCommitStatus(checkName, 'SUCCESS', "(${timeInfo}) Check is successful. ${testsInfo}".trim())
            break
        case 'UNSTABLE':
            updateGithubCommitStatus(checkName, 'FAILURE', "(${timeInfo}) Test failures occurred. ${testsInfo}".trim())
            break
        case 'ABORTED':
            updateGithubCommitStatus(checkName, 'ERROR', "(${timeInfo}) Job aborted. ${testsInfo}".trim())
            break
        default:
            updateGithubCommitStatus(checkName, 'ERROR', "(${timeInfo}) Issue in pipeline. ${testsInfo}".trim())
            break
    }
}
