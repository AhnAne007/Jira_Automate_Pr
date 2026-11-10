# Merge a branch, move the Jira ticket

A merged pull request is the only event this lab automates.

- Pull request merged into `develop` moves the ticket to **In Development**
- Pull request merged into `master` moves the ticket to **Ready for test**

The ticket key is read from the source branch name, the pull request title, the pull request body, or a commit subject. `feature/LAB-1-hello` and a title that contains `LAB-1` both update `LAB-1`.

Docker is not used. Jenkins is not installed. The Spring Boot service is the code you merge. The workflow does not build it and does not start it.

At work, Jenkins already hears about the merge and deploys to Rancher. The program in `ci/JiraTransition.java` is the extra stage you would run after that deploy succeeds. This PC has no Jenkins, so GitHub Actions runs that same program when you click Merge.

## What is already on this PC

- Git is installed at `C:\Program Files\Git`. If a terminal cannot find `git`, open a new terminal so `C:\Program Files\Git\cmd` is on the PATH.
- JDK 27 is installed at `C:\Program Files\Java\jdk-27.0.1.0.3`. The service compiles as Java 17, which that JDK can build. Do not install a second JDK.
- Open this folder in IntelliJ or in Cursor. In IntelliJ, set Git to `C:\Program Files\Git\cmd\git.exe` if it is not detected.

## GitHub: use the account you already have

Do not create a second GitHub user.

1. Sign in at https://github.com.
2. Open Settings → Emails. Copy a verified email, or the `users.noreply.github.com` address. The noreply address still attaches commits to your account. It does not receive mail, so do not use it for Jira.
3. Create an empty private repository named `jira-deploy-lab`. Do not add a generated README or `.gitignore`. You will push this folder into it.

In a terminal, point Git at that identity. Use your GitHub name and the email you copied:

```powershell
& "C:\Program Files\Git\cmd\git.exe" config --global user.name "Your Name"
& "C:\Program Files\Git\cmd\git.exe" config --global user.email "you@example.com"
```

From this folder, add the empty repository and push both branches. The first push opens a browser through Git Credential Manager. Sign in to the same GitHub account. That sign-in links this PC.

```powershell
git remote add origin https://github.com/YOUR_ACCOUNT/jira-deploy-lab.git
git push -u origin master
git push -u origin develop
```

In IntelliJ the same link is Settings → Version Control → GitHub → Log In via GitHub.

`master` and `develop` already exist locally and point at the same first commit, so the workflow is on both branches before you open a pull request.

## Jira: a personal inbox, not the company site

Atlassian emails you a verification link. Use a personal Gmail you can open. If you own `you@gmail.com` and want the lab mail kept apart, sign up as `you+jira-lab@gmail.com`. Those messages still arrive in that same Gmail.

1. Create the free site at https://www.atlassian.com/software/jira/free.
2. Create a team-managed software project with key `LAB`.
3. Add statuses **In Development** and **Ready for test**.
4. Add workflow arrows **To Do → In Development** and **In Development → Ready for test**. Jira rejects a status change that has no arrow.
5. Create an issue and leave it in **To Do**. The key will look like `LAB-1`.
6. Create an API token at https://id.atlassian.com/manage-profile/security/api-tokens.

On the GitHub repository, open Settings → Secrets and variables → Actions → New repository secret. Add these three secrets:

- `JIRA_BASE_URL`, for example `https://your-site.atlassian.net`
- `JIRA_EMAIL`, the Atlassian login email
- `JIRA_API_TOKEN`, the token from the step above

The token stays in GitHub secrets. It is not written into this repository.

## Try the two merges

1. Create branch `feature/LAB-1-hello`. Change the message in `service/src/main/java/com/lab/jiralab/HelloController.java`. Commit and push the branch.
2. Open a pull request into `develop`. The branch name or the title must contain `LAB-1`. Merge it on GitHub.
3. Open the Actions tab. The **Jira status** workflow should finish green, and `LAB-1` should be **In Development**.
4. Open a pull request from `develop` into `master`. Put `LAB-1` in the title, because the branch name `develop` has no ticket key. Merge it.
5. `LAB-1` should become **Ready for test**.

If the status stays put, open the workflow log. It says whether the key was missing, the token was rejected, or the workflow has no arrow from the current status.

## What each file is for

- `service` is one Spring Boot microservice, the kind of repository you merge at work. `GET /health` returns a short message you can change on the feature branch. Run it from `service` with `.\mvnw.cmd spring-boot:run` only if you want to see the app. The ticket move does not need that.
- `ci/JiraTransition.java` reads the pull request text, finds keys shaped like `LAB-1`, asks Jira which transitions are legal, and posts the one whose destination is the status for that branch.
- `.github/workflows/jira-status.yml` starts that program only after a pull request into `develop` or `master` is merged.

## How this maps onto work later

Leave the company build and Rancher deploy as they are. After that deploy succeeds, run the same transition call from the Jenkins job, with the token stored as a Jenkins credential. Your service repositories at work can stay without a workflow file, because Jenkins is what sees those merges.
