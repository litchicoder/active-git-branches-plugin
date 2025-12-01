# Active Git Branches Parameter Plugin

A Jenkins plugin that provides a build parameter for selecting Git branches dynamically fetched from a remote repository, sorted by commit date (most recent first), with configurable filtering and limiting options.

## Features

- **Dynamic Branch Fetching**: Automatically fetches branches from a Git repository at build time
- **Sorted by Activity**: Branches are sorted by commit date in descending order (most recent first)
- **Configurable Limit**: Limit the number of displayed branches (Top N)
- **Regex Filtering**: Filter branches using regular expressions
- **Credentials Support**: Integrates with Jenkins Credentials for private repositories
- **No Script Approval Required**: Pure Java implementation, no Groovy scripts or sandbox approval needed

## Requirements

- Jenkins 2.387.3 or later
- Java 11 or later
- Git Plugin

## Installation

### From Source

1. Clone this repository
2. Build the plugin:
   ```bash
   mvn clean package
   ```
3. Install the generated `.hpi` file from `target/active-git-branches-plugin.hpi` via Jenkins Plugin Manager

### From Jenkins Update Center

*(Coming soon)*

## Usage

### Pipeline (Declarative)

```groovy
pipeline {
    agent any
    parameters {
        activeGitBranches(
            name: 'BRANCH',
            repositoryUrl: 'https://github.com/your-org/your-repo.git',
            credentialsId: 'github-credentials',
            maxBranchCount: 10,
            branchFilter: 'feature/.*',
            description: 'Select a branch to build'
        )
    }
    stages {
        stage('Build') {
            steps {
                echo "Building branch: ${params.BRANCH}"
                git branch: params.BRANCH, 
                    url: 'https://github.com/your-org/your-repo.git',
                    credentialsId: 'github-credentials'
            }
        }
    }
}
```

### Pipeline (Scripted)

```groovy
properties([
    parameters([
        activeGitBranches(
            name: 'BRANCH',
            repositoryUrl: 'https://github.com/your-org/your-repo.git',
            maxBranchCount: 15,
            description: 'Select a branch'
        )
    ])
])

node {
    stage('Build') {
        echo "Selected branch: ${params.BRANCH}"
    }
}
```

### Freestyle Job

1. Go to your job's configuration page
2. Check "This project is parameterized"
3. Click "Add Parameter" and select "Active Git Branches Parameter"
4. Configure the following options:
   - **Parameter Name**: The name of the parameter (e.g., `BRANCH`)
   - **Git Repository URL**: The URL of your Git repository
   - **Credentials**: Select credentials for private repositories (optional)
   - **Max Branch Count**: Maximum number of branches to display (default: 10)
   - **Branch Filter**: Regular expression to filter branches (optional)
   - **Default Value**: Pre-selected branch (optional)

## Configuration Options

| Option | Required | Description |
|--------|----------|-------------|
| `name` | Yes | Parameter name |
| `repositoryUrl` | Yes | Git repository URL (HTTPS or SSH) |
| `credentialsId` | No | Jenkins credentials ID for private repositories |
| `maxBranchCount` | Yes | Maximum number of branches to display (1-100) |
| `branchFilter` | No | Regular expression to filter branch names |
| `defaultValue` | No | Default selected branch |
| `description` | No | Parameter description |

## Branch Filter Examples

| Pattern | Description |
|---------|-------------|
| `.*` | Match all branches |
| `feature/.*` | Only feature branches |
| `release/.*` | Only release branches |
| `(main\|master\|develop)` | Only main, master, or develop |
| `(?!release/).*` | Exclude release branches |
| `hotfix-.*\|bugfix-.*` | Hotfix or bugfix branches |

## Development

### Prerequisites

- JDK 11 or later
- Maven 3.8+

### Building

```bash
mvn clean package
```

### Running locally

```bash
mvn hpi:run
```

This will start a local Jenkins instance at http://localhost:8080/jenkins with the plugin installed.

### Running tests

```bash
mvn test
```

## How It Works

1. When the build parameter page loads, the plugin fetches remote branches from the configured Git repository
2. Branches are retrieved using JGit and sorted by commit date (descending)
3. The branch filter regex is applied (if configured)
4. The list is truncated to the configured maximum count
5. The filtered and sorted list is displayed as a dropdown

## Troubleshooting

### No branches displayed

- Verify the repository URL is correct
- Check that credentials are configured for private repositories
- Use the "Test Connection" button in the configuration

### Performance issues

- Reduce the `maxBranchCount` value
- The first load may be slower as the repository is being cloned temporarily

### Authentication errors

- Ensure credentials have read access to the repository
- For GitHub, use a Personal Access Token instead of password
- For SSH, ensure the SSH key is properly configured in Jenkins

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

## License

MIT License - see [LICENSE](LICENSE) for details.
