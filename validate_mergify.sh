response=$(curl -L -F 'data=@.github/mergify.yml' https://engine.mergify.io/validate)

if [[ ${response} == "The configuration is valid" ]]; then 
    exit 0
else 
    exit 1
fi
