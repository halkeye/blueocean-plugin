package io.jenkins.blueocean.blueocean_github_pipeline;

import com.google.common.collect.ImmutableMap;
import io.jenkins.blueocean.commons.ErrorMessage;
import io.jenkins.blueocean.commons.ServiceException;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHContent;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Github SCM save file API
 *
 * @author Vivek Pandey
 */
public class GithubScmSaveFileRequest{
    private final GithubContent content;

    @DataBoundConstructor
    public GithubScmSaveFileRequest(GithubContent content) {
        this.content = content;
    }

    public Object save(@Nonnull String apiUrl, @Nullable String owner, @Nullable String repoName, @Nullable String accessToken) {
        List<ErrorMessage.Error> errors = new ArrayList<>();

        if(this.content == null){
            errors.add(new ErrorMessage.Error("content",
                    ErrorMessage.Error.ErrorCodes.MISSING.toString(), "content is required parameter"));
            throw new ServiceException.BadRequestExpception(new ErrorMessage(400, "Failed to save file to scm").addAll(errors));
        }else {
            errors.addAll(content.validate());
        }


        //if no owner given then check if its there in request
        if(owner == null){
            owner = content.getOwner();
        }
        if(repoName == null){
            repoName = content.getRepo();
        }

        if(owner == null){
            errors.add(new ErrorMessage.Error("content.owner",
                    ErrorMessage.Error.ErrorCodes.MISSING.toString(), "No scm owner found with pipeline %s, please provide content.owner parameter"));
        }
        if(repoName == null){
            errors.add(new ErrorMessage.Error("content.repo",
                    ErrorMessage.Error.ErrorCodes.MISSING.toString(), "No scm repo found with pipeline %s, please provide content.repo parameter"));
        }
        if(errors.size() > 0) {
            throw new ServiceException.BadRequestExpception(new ErrorMessage(400, "Failed to save content").addAll(errors));
        }

        if(!errors.isEmpty()){
            throw new ServiceException.BadRequestExpception(new ErrorMessage(400, "Failed to save file to scm").addAll(errors));
        }

        try {
            String sha = content.getSha();
            //Lets check if this branch exists, if not then create it
            if(!StringUtils.isBlank(content.getBranch()) && (content.isAutoCreateBranch() == null || content.isAutoCreateBranch())){
                try {
                    HttpRequest.head(String.format("%s/repos/%s/%s/branches/%s",
                            apiUrl,
                            owner,
                            repoName,
                            content.getBranch())).withAuthorization("token "+accessToken).to(String.class);
                } catch (ServiceException.NotFoundException e) {
                    //branch doesn't exist, lets create new one

                    //1. Find commit sha off which this branch will be created
                    //2. We need to find default branch first
                    GHRepoEx repo = HttpRequest.get(String.format("%s/repos/%s/%s", apiUrl,
                            owner, repoName))
                            .withAuthorization("token "+accessToken).to(GHRepoEx.class);

                    //3. Get default branch's commit sha
                    GHBranch branch = HttpRequest.get(String.format("%s/repos/%s/%s/branches/%s",
                            apiUrl,
                            owner,
                            repoName,
                            repo.getDefaultBranch())).withAuthorization("token " + accessToken).to(GHBranch.class);

                    //4. create this missing branch. We ignore the response, if no error branch was created
                    HttpRequest.post(String.format("%s/repos/%s/%s/git/refs",
                            apiUrl,
                            owner,
                            repoName))
                            .withAuthorization("token " + accessToken)
                            .withBody(ImmutableMap.of("ref", "refs/heads/" + content.getBranch(),
                                    "sha", branch.getSHA1()))
                            .to(Map.class);

                    //5. Check and see if this path exists on this new branch, if it does,
                    //   get its sha so that we can update this file
                    try {
                        GHContent ghContent = HttpRequest.get(String.format("%s/repos/%s/%s/contents/%s",
                                apiUrl,
                                owner,
                                repoName,
                                content.getPath()))
                                .withAuthorization("token " + accessToken)
                                .to(GHContent.class);
                        if(!StringUtils.isBlank(sha) && !sha.equals(ghContent.getSha())){
                            throw new ServiceException.BadRequestExpception(String.format("sha in request: %s is different from sha of file %s in branch %s",
                                    sha, content.getPath(), content.getBranch()));
                        }
                        sha = ghContent.getSha();
                    }catch (ServiceException.NotFoundException e1){
                        //not found, ignore it, we are good
                    }
                }
            }

            Map<String,Object> body = new HashMap<>();
            body.put("message", content.getMessage());
            body.put("content", content.getBase64Data());

            if(!StringUtils.isBlank(content.getBranch())){
                body.put("branch", content.getBranch());
            }
            if(!StringUtils.isBlank(sha)){
                body.put("sha", sha);
            }
            final Map ghResp = HttpRequest.put(String.format("%s/repos/%s/%s/contents/%s",
                    apiUrl,
                    owner,
                    repoName,
                    content.getPath()))
                    .withAuthorization("token "+accessToken)
                    .withBody(body)
                    .to(Map.class);

            if(ghResp == null){
                throw new ServiceException.UnexpectedErrorException("Failed to save file to Github: "+content.getPath());
            }

            final Map ghContent = (Map) ghResp.get("content");

            if(ghContent == null){
                throw new ServiceException.UnexpectedErrorException("Failed to save file: "+content.getPath());
            }

            return new GithubFile(new GithubContent.Builder()
                    .sha((String)ghContent.get("sha"))
                    .name((String) ghContent.get("name"))
                    .repo(repoName)
                    .owner(owner)
                    .path((String) ghContent.get("path"))
                    .build());
        } catch (IOException e) {
            throw new ServiceException.UnexpectedErrorException("Failed to save file: "+e.getMessage(), e);
        }
    }
}